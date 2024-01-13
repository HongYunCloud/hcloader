package ink.bgp.hcloader.gradle;

import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.gradle.api.GradleException;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.file.CopyActionProcessingStreamAction;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.bundling.internal.Zip64RequiredException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.function.Function;

@RequiredArgsConstructor
public class HcLoaderCopyAction implements CopyAction {
  /**
   * Note that setting the January 1st 1980 (or even worse, "0", as time) won't work due
   * to Java 8 doing some interesting time processing: It checks if this date is before January 1st 1980
   * and if it is it starts setting some extra fields in the zip. Java 7 does not do that - but in the
   * zip not the milliseconds are saved but values for each of the date fields - but no time zone. And
   * 1980 is the first year which can be saved.
   * If you use January 1st 1980 then it is treated as a special flag in Java 8.
   * Moreover, only even seconds can be stored in the zip file. Java 8 uses the upper half of
   * some other long to store the remaining millis while Java 7 doesn't do that. So make sure
   * that your seconds are even.
   * Moreover, parsing happens via `new Date(millis)` in {@link java.util.zip.ZipUtils}#javaToDosTime() so we
   * must use default timezone and locale.
   * <p>
   * The date is 1980 February 1st CET.
   */
  public static final long CONSTANT_TIME_FOR_ZIP_ENTRIES = new GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis();

  private final @NotNull File zipFile;
  private final @NotNull Function<File, ZipArchiveOutputStream> compressor;
  private final @NotNull DocumentationRegistry documentationRegistry;
  private final @NotNull String encoding;
  private final boolean preserveFileTimestamps;

  private final @NotNull String loaderPackage;
  private final boolean enableStaticInject;
  private final @NotNull String staticInjectName;

  @Override
  public @NotNull WorkResult execute(final @NotNull CopyActionProcessingStream stream) {
    try (final ZipArchiveOutputStream zipOutStr = compressor.apply(zipFile)) {
      try {
        StreamAction action = new StreamAction(zipOutStr, encoding);
        stream.process(action);
        action.collect();
      } catch (final UncheckedIOException e) {
        if (e.getCause() instanceof Zip64RequiredException) {
          throw new org.gradle.api.tasks.bundling.internal.Zip64RequiredException(
              String.format("%s\n\nTo build this archive, please enable the zip64 extension.\nSee: %s", e.getCause().getMessage(), documentationRegistry.getDslRefForProperty(Zip.class, "zip64"))
          );
        }
      }
    } catch (IOException e) {
      throw new GradleException(String.format("Could not create ZIP '%s'.", zipFile), e);
    }

    return WorkResults.didWork(true);
  }

  private long getArchiveTimeFor(FileCopyDetails details) {
    return preserveFileTimestamps ? details.getLastModified() : CONSTANT_TIME_FOR_ZIP_ENTRIES;
  }

  private class StreamAction implements CopyActionProcessingStreamAction {
    private final ZipArchiveOutputStream zipOutStr;
    private boolean visitedTarget = false;

    public StreamAction(final @NotNull ZipArchiveOutputStream zipOutStr, final @Nullable String encoding) {
      this.zipOutStr = zipOutStr;
      if (encoding != null) {
        this.zipOutStr.setEncoding(encoding);
      }
    }

    @Override
    public void processFile(FileCopyDetailsInternal details) {
      if (!details.isDirectory()) {
        visitFile(details);
      }
    }

    private void injectNormalMethodNode(String targetInternalName, MethodNode methodNode) {
      methodNode.instructions.insertBefore(
          methodNode.instructions.getFirst(),
          new MethodInsnNode(Opcodes.INVOKESTATIC, targetInternalName, "$hcloader$ensureloaded", "()V"));
    }

    private void injectTargetMethodNode(String targetInternalName, MethodNode methodNode) {
      InsnList insn = new InsnList();
      insn.add(new LdcInsnNode(Type.getObjectType(targetInternalName)));
      insn.add(new MethodInsnNode(
          Opcodes.INVOKESTATIC,
          loaderPackage.replace('.', '/') + "/StaticInjector",
          "inject",
          "(Ljava/lang/Class;)V",
          false));
      methodNode.instructions.insertBefore(methodNode.instructions.getFirst(), insn);

      methodNode.maxStack++;
    }

    private MethodNode createTargetEnsureMethodNode() {
      MethodNode methodNode = new MethodNode(
          Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
          "$hcloader$ensureloaded",
          "()V",
          null,
          null);

      methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
      return methodNode;
    }

    private void processClassNode(ClassNode classNode) {
      final String targetInternalName = staticInjectName.replace('.', '/');

      MethodNode clinitMethod = classNode.methods
          .stream()
          .filter(it-> (it.access & Opcodes.ACC_STATIC) != 0
              && it.name.equals("<clinit>")
              && it.desc.equals("()V"))
          .findFirst()
          .orElseGet(()->{
            final MethodNode newMethodNode = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            newMethodNode.maxLocals = 0;
            newMethodNode.maxStack = 0;
            newMethodNode.instructions.add(new InsnNode(Opcodes.RETURN));
            classNode.methods.add(newMethodNode);
            return newMethodNode;
          });

      if (classNode.name.equals(targetInternalName)) {
        visitedTarget = true;
        injectTargetMethodNode(targetInternalName, clinitMethod);
        classNode.methods.add(createTargetEnsureMethodNode());
      } else {
        injectNormalMethodNode(targetInternalName, clinitMethod);
      }
    }

    private byte[] processContent(byte[] bytes) throws IOException {
      final ClassNode classNode = new ClassNode();
      new ClassReader(bytes).accept(classNode, 0);

      if ((classNode.access & Opcodes.ACC_MODULE) != 0) {
        return null;
      }

      processClassNode(classNode);

      final ClassWriter classWriter = new ClassWriter(0);
      classNode.accept(classWriter);
      return classWriter.toByteArray();
    }

    private void visitFile(FileCopyDetails fileDetails) {
      try {
        ZipArchiveEntry archiveEntry = new ZipArchiveEntry(fileDetails.getRelativePath().getPathString());
        archiveEntry.setTime(getArchiveTimeFor(fileDetails));
        archiveEntry.setUnixMode(UnixStat.FILE_FLAG | fileDetails.getPermissions().toUnixNumeric());
        zipOutStr.putArchiveEntry(archiveEntry);

        byte[] result;

        if (enableStaticInject
            // && archiveEntry.getName().endsWith(".class")
            // && !archiveEntry.getName().startsWith(loaderPackage.replace('.', '/') + "/")
            && archiveEntry.getName().equals(staticInjectName.replace('.', '/') + ".class")
        ) {
          final ByteArrayOutputStream bout = new ByteArrayOutputStream();
          fileDetails.copyTo(bout);
          result = processContent(bout.toByteArray());
        } else {
          result = null;
        }

        if (result == null) {
          fileDetails.copyTo(zipOutStr);
        } else {
          zipOutStr.write(result);
        }

        zipOutStr.closeArchiveEntry();
      } catch (Exception e) {
        throw new GradleException(String.format("Could not add %s to ZIP '%s'.", fileDetails, zipFile), e);
      }
    }

    public void collect() throws IOException {
      if(enableStaticInject && !visitedTarget) {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.version = Opcodes.V1_8;
        classNode.access = Opcodes.ACC_PUBLIC;
        classNode.name = staticInjectName.replace('.', '/');
        classNode.superName = "java/lang/Object";
        processClassNode(classNode);

        ZipArchiveEntry archiveEntry = new ZipArchiveEntry(classNode.name + ".class");
        archiveEntry.setTime(preserveFileTimestamps ? System.currentTimeMillis() : CONSTANT_TIME_FOR_ZIP_ENTRIES);
        archiveEntry.setUnixMode(UnixStat.FILE_FLAG);
        zipOutStr.putArchiveEntry(archiveEntry);
        final ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classNode.accept(classWriter);
        zipOutStr.write(classWriter.toByteArray());
        zipOutStr.closeArchiveEntry();
      }
    }
  }
}
