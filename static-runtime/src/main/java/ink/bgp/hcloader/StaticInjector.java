package ink.bgp.hcloader;

import ink.bgp.hcloader.archive.JarFileArchive;
import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.function.Consumer;

public final class StaticInjector {
  private StaticInjector() {
    throw new UnsupportedOperationException();
  }

  public static void inject(final @NotNull Class<?> targetClass) throws Throwable {
    final Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
    theUnsafeField.setAccessible(true);
    final Unsafe unsafe = (Unsafe) theUnsafeField.get(null);

    final Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
    final MethodHandles.Lookup lookup = (MethodHandles.Lookup) unsafe.getObject(
        unsafe.staticFieldBase(implLookupField),
        unsafe.staticFieldOffset(implLookupField));

    final URLClassLoader targetClassLoader = (URLClassLoader) targetClass.getClassLoader();
    final File pluginFile = new File(targetClass.getProtectionDomain().getCodeSource().getLocation().getFile());
    final JarFileArchive pluginArchive = new JarFileArchive(pluginFile);

    final LaunchedURLClassLoader delegateClassLoader = new LaunchedURLClassLoader(pluginArchive, new URL[0], targetClassLoader);
    final MethodHandle urlClassLoaderAddUrlHandle = lookup.findVirtual(URLClassLoader.class, "addURL", MethodType.methodType(void.class, URL.class));

    load(urlClassLoaderAddUrlHandle, targetClassLoader, delegateClassLoader, pluginArchive);
    scanDelegateConfig(targetClassLoader, delegateClassLoader);

    for (final Field field : targetClass.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || !field.getName().startsWith("$hcloader$")) {
        continue;
      }
      final Object value;
      switch (field.getName()) {
        case "$hcloader$delegateClassLoader": {
          value = field.getType().cast(delegateClassLoader);
          break;
        }
        case "$hcloader$unsafe": {
          value = field.getType().cast(unsafe);
          break;
        }
        case "$hcloader$lookup": {
          value = field.getType().cast(lookup);
          break;
        }
        case "$hcloader$file": {
          value = field.getType().cast(pluginFile);
          break;
        }
        case "$hcloader$addDelegateFile": {
          value = field.getType().cast((Consumer<File>) file -> {
            try {
              load(urlClassLoaderAddUrlHandle, targetClassLoader, delegateClassLoader, new JarFileArchive(file));
              scanDelegateConfig(targetClassLoader, delegateClassLoader);
            } catch (Throwable e) {
              throwImpl(e);
            }
          });
          break;
        }
        default: {
          continue;
        }
      }
      if(Modifier.isVolatile(field.getModifiers())) {
        unsafe.putObjectVolatile(
            unsafe.staticFieldBase(field),
            unsafe.staticFieldOffset(field),
            value);
      } else {
        unsafe.putObject(
            unsafe.staticFieldBase(field),
            unsafe.staticFieldOffset(field),
            value);
      }
    }
  }

  private static void scanDelegateConfig(
      final @NotNull URLClassLoader targetClassLoader,
      final @NotNull LaunchedURLClassLoader delegateClassLoader) throws IOException {
    final Enumeration<URL> delegateConfigUrls = targetClassLoader.findResources("META-INF/hcloader/delegateconfig");
    while (delegateConfigUrls.hasMoreElements()) {
      URL delegateConfigUrl = delegateConfigUrls.nextElement();
      try (InputStream in = delegateConfigUrl.openStream()) {
        LoadConfigEntry.load(in, delegateClassLoader);
      }
    }
  }

  private static void load(
      final @NotNull MethodHandle urlClassLoaderAddUrlHandle,
      final @NotNull URLClassLoader targetClassLoader,
      final @NotNull LaunchedURLClassLoader launchedURLClassLoader,
      final @NotNull JarFileArchive jarFileArchive) throws Throwable {
    urlClassLoaderAddUrlHandle.invokeExact(targetClassLoader, jarFileArchive.getUrl());

    jarFileArchive.getNestedArchives(
        entry -> entry.getName().startsWith("META-INF/hcloader/embedded/"),
        entry -> entry.getName().startsWith("META-INF/hcloader/embedded/")
    ).forEachRemaining(archive -> {
      try {
        urlClassLoaderAddUrlHandle.invokeExact(targetClassLoader, archive.getUrl());
      } catch (Throwable e) {
        throw throwImpl(e);
      }
    });

    jarFileArchive.getNestedArchives(
        entry -> entry.getName().startsWith("META-INF/hcloader/delegate/"),
        entry -> entry.getName().startsWith("META-INF/hcloader/delegate/")
    ).forEachRemaining(it -> {
      try {
        launchedURLClassLoader.addURL(it.getUrl());
      } catch (MalformedURLException e) {
        throw throwImpl(e);
      }
    });
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> RuntimeException throwImpl(final @NotNull Throwable e) throws T {
    throw (T) e;
  }
}
