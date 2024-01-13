/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ink.bgp.hcloader;

import ink.bgp.hcloader.archive.Archive;
import ink.bgp.hcloader.jar.Handler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * @author Phillip Webb
 * @author Dave Syer
 * @author Andy Wilkinson
 * @since 1.0.0
 */
/* package-private */ class LaunchedURLClassLoader extends URLClassLoader {
  private static final @NotNull ClassLoader NULL_CLASS_LOADER = new ClassLoader(null) {
  };
  private static final int BUFFER_SIZE = 4096;

  static {
    ClassLoader.registerAsParallelCapable();
  }


  private final Archive rootArchive;

  private final @NotNull Set<LoadConfigEntry> loadConfigEntries = Collections.synchronizedSet(new TreeSet<>());
  private final Object packageLock = new Object();

  private volatile DefinePackageCallType definePackageCallType;

  /**
   * Create a new {@link LaunchedURLClassLoader} instance.
   *
   * @param urls   the URLs from which to load classes and resources
   * @param parent the parent class loader for delegation
   */
  public LaunchedURLClassLoader(URL[] urls, ClassLoader parent) {
    this(null, urls, parent);
  }

  /**
   * Create a new {@link LaunchedURLClassLoader} instance.
   *
   * @param rootArchive the root archive or {@code null}
   * @param urls        the URLs from which to load classes and resources
   * @param parent      the parent class loader for delegation
   * @since 2.3.1
   */
  public LaunchedURLClassLoader(Archive rootArchive, URL[] urls, ClassLoader parent) {
    super(urls, parent);
    this.rootArchive = rootArchive;
  }

  private @NotNull LoadConfigEntry getLoadConfig(final @NotNull String name) {
    for (final LoadConfigEntry configEntry : loadConfigEntries) {
      if (configEntry.glob().matches(name)) {
        return configEntry;
      }
    }
    return LoadConfigEntry.fallback();
  }

  private @NotNull ClassLoader parent() {
    final ClassLoader parent = getParent();
    return parent == null ? NULL_CLASS_LOADER : parent;
  }

  @Override
  public @Nullable URL getResource(final @NotNull String name) {
    return getResource0(null, name);
  }

  private @Nullable URL getResource0(
      final @Nullable LoadConfigEntry rawLoadConfig,
      final @NotNull String name) {
    final LoadConfigEntry loadConfig = (rawLoadConfig == null) ? getLoadConfig(name) : rawLoadConfig;
    URL url = null;
    if (loadConfig.policy().selfFirst()) {
      url = findResource0(loadConfig, name);
    }
    if (url == null && loadConfig.policy().parentSecond()) {
      url = parent().getResource(name);
    }
    if (url == null && loadConfig.policy().selfThird()) {
      url = findResource0(loadConfig, name);
    }
    return url;
  }

  @Override
  public @NotNull Enumeration<@NotNull URL> getResources(final @NotNull String name) throws IOException {
    return getResources0(null, name);
  }

  private @NotNull Enumeration<@NotNull URL> getResources0(
      final @Nullable LoadConfigEntry rawLoadConfig,
      final @NotNull String name) throws IOException {
    final LoadConfigEntry loadConfig = (rawLoadConfig == null) ? getLoadConfig(name) : rawLoadConfig;
    final Enumeration<URL>[] enumerations = new Enumeration[3];

    if (loadConfig.policy().selfFirst()) {
      enumerations[0] = findResources0(loadConfig, name);
    }
    if (loadConfig.policy().parentSecond()) {
      enumerations[1] = findResources0(loadConfig, name);
    }
    if (loadConfig.policy().selfThird()) {
      enumerations[2] = findResources0(loadConfig, name);
    }
    return new UseFastConnectionExceptionsEnumeration(enumerations);
  }

  @Override
  public @Nullable URL findResource(final @NotNull String name) {
    return findResource0(null, name);
  }

  private @Nullable URL findResource0(
      final @Nullable LoadConfigEntry rawLoadConfig,
      final @NotNull String name) {
    final LoadConfigEntry loadConfig = (rawLoadConfig == null) ? getLoadConfig(name) : rawLoadConfig;
    if (loadConfig.policy().selfEnabled()) {
      Handler.setUseFastConnectionExceptions(true);
      try {
        return super.findResource(name);
      } finally {
        Handler.setUseFastConnectionExceptions(false);
      }
    } else {
      return null;
    }
  }

  @Override
  public @NotNull Enumeration<@NotNull URL> findResources(String name) throws IOException {
    return findResources0(null, name);
  }

  private @NotNull Enumeration<@NotNull URL> findResources0(
      final @Nullable LoadConfigEntry rawLoadConfig,
      final @NotNull String name) throws IOException {
    final LoadConfigEntry loadConfig = (rawLoadConfig == null) ? getLoadConfig(name) : rawLoadConfig;
    if (loadConfig.policy().selfEnabled()) {
      Handler.setUseFastConnectionExceptions(true);
      try {
        return super.findResources(name);
      } finally {
        Handler.setUseFastConnectionExceptions(false);
      }
    } else {
      return Collections.emptyEnumeration();
    }
  }

  @Override
  protected @NotNull Class<?> loadClass(final @NotNull String name, final boolean resolve) throws ClassNotFoundException {
    return loadClass0(null, name, resolve);
  }

  protected @NotNull Class<?> loadClass0(
      final @Nullable LoadConfigEntry rawLoadConfig,
      final @NotNull String name,
      final boolean resolve) throws ClassNotFoundException {
    final LoadConfigEntry loadConfig = (rawLoadConfig == null) ? getLoadConfig(name.replace('.', '/') + ".class") : rawLoadConfig;

    Handler.setUseFastConnectionExceptions(true);
    try {
      try {
        definePackageIfNecessary(name);
      } catch (IllegalArgumentException ex) {
        // Tolerate race condition due to being parallel capable
        if (getPackage(name) == null) {
          // This should never happen as the IllegalArgumentException indicates
          // that the package has already been defined and, therefore,
          // getPackage(name) should not return null.
          throw new AssertionError("Package " + name + " has already been defined but it could not be found");
        }
      }

      Class<?> clazz = null;
      if (loadConfig.policy().selfFirst()) {
        try {
          clazz = findClass0(loadConfig, name);
        } catch (final ClassNotFoundException e) {
          //
        }
      }
      if (loadConfig.policy().parentSecond()) {
        try {
          clazz = parent().loadClass(name);
        } catch (final ClassNotFoundException e) {
          //
        }
      }
      if (loadConfig.policy().selfThird()) {
        try {
          clazz = findClass0(loadConfig, name);
        } catch (final ClassNotFoundException e) {
          //
        }
      }
      if (clazz == null) {
        throw new ClassNotFoundException(name);
      } else {
        return clazz;
      }
    } finally {
      Handler.setUseFastConnectionExceptions(false);
    }
  }

  @Override
  protected @NotNull Class<?> findClass(final @NotNull String name) throws ClassNotFoundException {
    return findClass0(null, name);
  }

  private @NotNull Class<?> findClass0(
      final @Nullable LoadConfigEntry rawLoadConfig,
      final @NotNull String name) throws ClassNotFoundException {
    final LoadConfigEntry loadConfig = (rawLoadConfig == null) ? getLoadConfig(name.replace('.', '/') + ".class") : rawLoadConfig;
    if (loadConfig.policy().selfEnabled()) {
      return super.findClass(name);
    } else {
      throw new ClassNotFoundException(name);
    }
  }

  /**
   * Define a package before a {@code findClass} call is made. This is necessary to
   * ensure that the appropriate manifest for nested JARs is associated with the
   * package.
   *
   * @param className the class name being found
   */
  private void definePackageIfNecessary(final @NotNull String className) {
		final int lastDot = className.lastIndexOf('.');
    if (lastDot >= 0) {
			final String packageName = className.substring(0, lastDot);
      if (getPackage(packageName) == null) {
        try {
          definePackage(className, packageName);
        } catch (final IllegalArgumentException ex) {
          // Tolerate race condition due to being parallel capable
          if (getPackage(packageName) == null) {
            // This should never happen as the IllegalArgumentException
            // indicates that the package has already been defined and,
            // therefore, getPackage(name) should not have returned null.
            throw new AssertionError(
                "Package " + packageName + " has already been defined but it could not be found");
          }
        }
      }
    }
  }

  private void definePackage(final @NotNull String className, final @NotNull String packageName) {
    try {
      AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
        final String packageEntryName = packageName.replace('.', '/') + "/";
				final String classEntryName = className.replace('.', '/') + ".class";
        for (final URL url : getURLs()) {
          try {
						final URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection) {
							final JarFile jarFile = ((JarURLConnection) connection).getJarFile();
              if (jarFile.getEntry(classEntryName) != null && jarFile.getEntry(packageEntryName) != null
                  && jarFile.getManifest() != null) {
                definePackage(packageName, jarFile.getManifest(), url);
                return null;
              }
            }
          } catch (final IOException ex) {
            // Ignore
          }
        }
        return null;
      }, AccessController.getContext());
    } catch (final java.security.PrivilegedActionException ex) {
      // Ignore
    }
  }

  @Override
  protected @NotNull Package definePackage(
			final @NotNull String name,
			final @NotNull Manifest man,
			final @NotNull URL url) throws IllegalArgumentException {
    synchronized (this.packageLock) {
      return doDefinePackage(DefinePackageCallType.MANIFEST, () -> super.definePackage(name, man, url));
    }
  }

  @Override
  protected @NotNull Package definePackage(
			final @NotNull String name,
			final @NotNull String specTitle,
			final @NotNull String specVersion,
			final @NotNull String specVendor,
			final @NotNull String implTitle,
			final @NotNull String implVersion,
			final @NotNull String implVendor,
			final @NotNull URL sealBase) throws IllegalArgumentException {
    synchronized (this.packageLock) {
      if (this.definePackageCallType == null) {
        // We're not part of a call chain which means that the URLClassLoader
        // is trying to define a package for our exploded JAR. We use the
        // manifest version to ensure package attributes are set
        final Manifest manifest = getManifest(this.rootArchive);
        if (manifest != null) {
          return definePackage(name, manifest, sealBase);
        }
      }
      return doDefinePackage(DefinePackageCallType.ATTRIBUTES, () -> super.definePackage(name, specTitle,
          specVersion, specVendor, implTitle, implVersion, implVendor, sealBase));
    }
  }

  private @Nullable Manifest getManifest(final @Nullable Archive archive) {
    try {
      return (archive != null) ? archive.getManifest() : null;
    } catch (IOException ex) {
      return null;
    }
  }

  private <T> T doDefinePackage(final @NotNull DefinePackageCallType type, final @NotNull Supplier<T> call) {
    final DefinePackageCallType existingType = this.definePackageCallType;
    try {
      this.definePackageCallType = type;
      return call.get();
    } finally {
      this.definePackageCallType = existingType;
    }
  }

  /**
   * Clear URL caches.
   */
  public void clearCache() {
    for (final URL url : getURLs()) {
      try {
        final URLConnection connection = url.openConnection();
        if (connection instanceof JarURLConnection) {
          clearCache(connection);
        }
      } catch (final IOException ex) {
        // Ignore
      }
    }

  }

  private void clearCache(final @NotNull URLConnection connection) throws IOException {
    final Object jarFile = ((JarURLConnection) connection).getJarFile();
    if (jarFile instanceof ink.bgp.hcloader.jar.JarFile) {
      ((ink.bgp.hcloader.jar.JarFile) jarFile).clearCache();
    }
  }

  @Override
  public void addURL(final @NotNull URL url) {
    super.addURL(url);
  }

  public void addConfig(final @NotNull LoadConfigEntry configEntry) {
    loadConfigEntries.add(configEntry);
  }

  /**
   * The different types of call made to define a package. We track these for exploded
   * jars so that we can detect packages that should have manifest attributes applied.
   */
  private enum DefinePackageCallType {

    /**
     * A define package call from a resource that has a manifest.
     */
    MANIFEST,

    /**
     * A define package call with a direct set of attributes.
     */
    ATTRIBUTES

  }

  private static class UseFastConnectionExceptionsEnumeration implements Enumeration<URL> {
    private final @NotNull Iterator<@NotNull Enumeration<@NotNull URL>> delegateIterator;
    private @Nullable Enumeration<@NotNull URL> current;

    UseFastConnectionExceptionsEnumeration(Enumeration<URL>... delegates) {
      this.delegateIterator = Arrays.asList(delegates).iterator();
    }

    private void update() {
      while (current == null || !current.hasMoreElements()) {
        if (delegateIterator.hasNext()) {
          current = delegateIterator.next();
        } else {
          current = null;
          return;
        }
      }
    }

    @Override
    public synchronized boolean hasMoreElements() {
      update();
      Handler.setUseFastConnectionExceptions(true);
      try {
        return current != null && current.hasMoreElements();
      } finally {
        Handler.setUseFastConnectionExceptions(false);
      }

    }

    @Override
    public synchronized URL nextElement() {
      update();
      Handler.setUseFastConnectionExceptions(true);
      try {
        if (current == null) {
          throw new NoSuchElementException();
        } else {
          return current.nextElement();
        }
      } finally {
        Handler.setUseFastConnectionExceptions(false);
      }
    }

  }

}
