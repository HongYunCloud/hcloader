package ink.bgp.hcloader;

import ink.bgp.hcloader.archive.JarFileArchive;
import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.io.File;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

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

    final JarFileArchive jarFileArchive = new JarFileArchive(pluginFile);

    // archive -> pluginClassLoader.addUrl(archive.getUrl())
    final MethodHandle urlClassLoaderAddUrlHandle = lookup.findVirtual(URLClassLoader.class, "addURL", MethodType.methodType(void.class, URL.class));

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

    final List<URL> delegateUrls = new ArrayList<>();
    jarFileArchive.getNestedArchives(
        entry -> entry.getName().startsWith("META-INF/hcloader/delegate/"),
        entry -> entry.getName().startsWith("META-INF/hcloader/delegate/")
    ).forEachRemaining(it-> {
      try {
        delegateUrls.add(it.getUrl());
      } catch (MalformedURLException e) {
        throw throwImpl(e);
      }
    });

    final LaunchedURLClassLoader delegateClassLoader = new LaunchedURLClassLoader(
        jarFileArchive,
        delegateUrls.toArray(new URL[0]),
        targetClassLoader);

    try(final InputStream in = targetClassLoader.getResourceAsStream("META-INF/hcloader/delegateconfig")) {
      if (in != null) {
        LoadConfigEntry.load(in, delegateClassLoader);
      }
    }

    for (final Field field : targetClass.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || !field.getName().startsWith("$hcloader$")) {
        return;
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

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> RuntimeException throwImpl(final @NotNull Throwable e) throws T {
    throw (T) e;
  }
}
