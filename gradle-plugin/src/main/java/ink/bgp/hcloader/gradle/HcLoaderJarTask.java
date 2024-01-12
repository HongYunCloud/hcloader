package ink.bgp.hcloader.gradle;

import lombok.SneakyThrows;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.bundling.ZipEntryCompression;
import org.gradle.internal.IoActions;
import org.gradle.internal.execution.OutputChangeListener;
import org.gradle.internal.serialization.Cached;
import org.gradle.jvm.tasks.Jar;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.gradle.api.internal.lambdas.SerializableLambdas.action;

@CacheableTask
public class HcLoaderJarTask extends Jar {
  private final @NotNull UUID instanceId = UUID.randomUUID();
  private final @NotNull Property<String> loaderPackage;
  private final @NotNull Property<Boolean> enableStaticInject;
  private final @NotNull Property<String> staticInjectClass;

  private final @NotNull List<@NotNull HcLoaderConfigEntry> loadConfig = new ArrayList<>();

  public HcLoaderJarTask() {
    this.loaderPackage = getObjectFactory().property(String.class)
        .convention(getProject().getGroup() + "." + getProject().getName() + ".loader");
    this.enableStaticInject = getObjectFactory().property(Boolean.class)
        .convention(false);
    this.staticInjectClass = getObjectFactory().property(String.class)
        .convention("");

    setEntryCompression(ZipEntryCompression.STORED);

    getArchiveClassifier().set("boot");

    final Cached<FileTree> runtimeCache = Cached.of(this::computeCache);
    from(getProject().provider(runtimeCache::get));
  }

  @Input
  public @NotNull Property<@NotNull String> getLoaderPackage() {
    return loaderPackage;
  }

  @Input
  public @NotNull Property<@NotNull Boolean> getEnableStaticInject() {
    return enableStaticInject;
  }

  @Input
  public @NotNull Property<@NotNull String> getStaticInjectClass() {
    return staticInjectClass;
  }

  @Input
  public @NotNull List<@NotNull HcLoaderConfigEntry> getLoadConfig() {
    return loadConfig;
  }

  private @NotNull String computeLoaderPackage() {
    final String[] loaderPackageSplits = this.loaderPackage.get().split("\\.");
    final List<String> loaderPackageParts = new ArrayList<>(loaderPackageSplits.length);

    boolean[] firstVisited = new boolean[1];

    for (final String packageSplit : loaderPackageSplits) {
      if (packageSplit == null || packageSplit.isEmpty()) {
        if(firstVisited[0]) {
          loaderPackageParts.add("_");
        } else {
          return "hc_loader_runtime_" + instanceId.toString().replace('-', '_');
        }
        continue;
      }
      final StringBuilder sb = new StringBuilder(packageSplit.length());
      packageSplit.codePoints().forEach(codePoint->{
        if (firstVisited[0]
            ? Character.isJavaIdentifierPart(codePoint)
            : Character.isJavaIdentifierStart(codePoint)) {
          sb.appendCodePoint(codePoint);
        } else {
          sb.append('_');
        }
        firstVisited[0] = true;
      });
      loaderPackageParts.add(sb.toString());
    }
    return String.join("/", loaderPackageParts);
  }

  public @NotNull FileTree computeCache() {
    final FileCollectionFactory fileCollectionFactory = getServices().get(FileCollectionFactory.class);
    final OutputChangeListener outputChangeListener = getServices().get(OutputChangeListener.class);

    final String loaderPackage = computeLoaderPackage();

    final Remapper remapper = new Remapper() {
      @Override
      public String map(String internalName) {
        if (internalName.startsWith("ink/bgp/hcloader/")) {
          return super.map(loaderPackage + "/" + internalName.substring("ink/bgp/hcloader/".length()));
        }
        return super.map(internalName);
      }
    };
    final List<FileTreeInternal> fileTreeList = new ArrayList<>();

    computeCacheEntry(fileCollectionFactory, outputChangeListener, remapper, fileTreeList, "hc-loader-runtime.jar");
    if(enableStaticInject.get()) {
      computeCacheEntry(fileCollectionFactory, outputChangeListener, remapper, fileTreeList, "hc-loader-static-runtime.jar");
    }

    fileTreeList.add(fileCollectionFactory.generated(
        getTemporaryDirFactory(),
        "META-INF/hcloader/delegateconfig",
        action(file -> outputChangeListener.invalidateCachesFor(
            Collections.singletonList(file.getAbsolutePath()))),
        action(this::writeLoaderConfig)
    ));

    return fileCollectionFactory.treeOf(fileTreeList);
  }

  @SneakyThrows
  private void writeLoaderConfig(final @NotNull OutputStream rawOut) {
    final DataOutputStream out = new DataOutputStream(rawOut);
    int loadConfigSize = loadConfig.size();
    out.writeInt(loadConfigSize);

    for (int i = 0; i < loadConfigSize; i++) {
      final HcLoaderConfigEntry entry = loadConfig.get(i);
      out.writeInt(entry.priority());
      out.writeUTF(entry.globPattern());
      out.writeUTF(entry.policy().name());
    }
    out.flush();
  }

  @SneakyThrows
  private void computeCacheEntry(
      final @NotNull FileCollectionFactory fileCollectionFactory,
      final @NotNull OutputChangeListener outputChangeListener,
      final @NotNull Remapper remapper,
      final @NotNull List<@NotNull FileTreeInternal> fileTreeList,
      final @NotNull String name) {
    try(final JarInputStream jarIn = new JarInputStream(HcLoaderJarTask.class.getResourceAsStream(name))) {
      JarEntry jarEntry;
      while ((jarEntry = jarIn.getNextJarEntry()) != null) {
        if (!jarEntry.getName().endsWith(".class")) {
          continue;
        }
        final ClassNode classNode = new ClassNode();
        new ClassReader(jarIn).accept(new ClassRemapper(classNode, remapper), 0);
        final ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        final byte[] bytes = classWriter.toByteArray();
        fileTreeList.add(fileCollectionFactory.generated(
            getTemporaryDirFactory(),
            classNode.name + ".class",
            action(file -> outputChangeListener.invalidateCachesFor(
                Collections.singletonList(file.getAbsolutePath()))),
            action(outputStream -> writeLibraries(outputStream, bytes))
        ));
      }
    }
  }

  protected @NotNull Function<@NotNull File, @NotNull ZipArchiveOutputStream> computeCompressorImpl() {
    switch (getEntryCompression()) {
      case DEFLATED:
        return new DefaultZipCompressor(isZip64(), ZipArchiveOutputStream.DEFLATED);
      case STORED:
        return new DefaultZipCompressor(isZip64(), ZipArchiveOutputStream.STORED);
      default:
        throw new IllegalArgumentException(String.format("Unknown Compression type %s", getEntryCompression()));
    }
  }

  @Override
  protected @NotNull CopyAction createCopyAction() {
    final DocumentationRegistry documentationRegistry = getServices().get(DocumentationRegistry.class);
    return new HcLoaderCopyAction(
        getArchiveFile().get().getAsFile(),
        computeCompressorImpl(),
        documentationRegistry,
        getMetadataCharset(),
        isPreserveFileTimestamps(),
        computeLoaderPackage(),
        enableStaticInject.get(),
        staticInjectClass.get());
  }

  @SneakyThrows
  private void writeLibraries(final @NotNull OutputStream outputStream, final byte @NotNull [] bytes) {
    outputStream.write(bytes);
  }

  private static final class DefaultZipCompressor implements Function<@NotNull File, @NotNull ZipArchiveOutputStream> {
    private final int entryCompressionMethod;
    private final @NotNull Zip64Mode zip64Mode;

    public DefaultZipCompressor(final boolean allowZip64Mode, final int entryCompressionMethod) {
      this.entryCompressionMethod = entryCompressionMethod;
      zip64Mode = allowZip64Mode ? Zip64Mode.AsNeeded : Zip64Mode.Never;
    }

    @Override
    @SneakyThrows
    public @NotNull ZipArchiveOutputStream apply(final @NotNull File destination) {
      final ZipArchiveOutputStream outStream = new ZipArchiveOutputStream(destination);
      try {
        outStream.setUseZip64(zip64Mode);
        outStream.setMethod(entryCompressionMethod);
        return outStream;
      } catch (final Exception e) {
        IoActions.closeQuietly(outStream);
        final String message = String.format("Unable to create ZIP output stream for file %s.", destination);
        throw new UncheckedIOException(message, e);
      }
    }
  }
}
