package ink.bgp.hcloader.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

public class HcLoaderGradlePlugin implements Plugin<Project> {
  @Override
  public void apply(final @NotNull Project project) {
    final Configuration delegateRuntimeConfiguration = project.getConfigurations().create("delegateRuntime");
    final Configuration shadowRuntimeConfiguration = project.getConfigurations().create("shadowRuntime");
    final Configuration embeddedRuntimeConfiguration = project.getConfigurations().create("embeddedRuntime");

    final TaskProvider<Jar> jarTask = project.getTasks().named("jar", Jar.class);

    final HcLoaderJarTask hcLoaderJarTask = project.getTasks().create("hcLoaderJar", HcLoaderJarTask.class);
    hcLoaderJarTask.dependsOn(jarTask);
    hcLoaderJarTask.with(project.copySpec(copySpec -> {
      copySpec.from(jarTask.map(jar -> jar.getOutputs()
          .getFiles()
          .getFiles()
          .stream()
          .map(it -> it.isFile() ? project.zipTree(it) : it)
          .collect(Collectors.toSet())));
      copySpec.exclude("META-INF/MANIFEST.MF");
    }));
    hcLoaderJarTask.with(project.copySpec(copySpec -> {
      copySpec.from(delegateRuntimeConfiguration);
      copySpec.into("META-INF/hcloader/delegate");
    }));
    hcLoaderJarTask.with(project.copySpec(copySpec -> {
      copySpec.from(embeddedRuntimeConfiguration);
      copySpec.into("META-INF/hcloader/embedded");
    }));
    hcLoaderJarTask.with(project.copySpec(copySpec ->
        copySpec.from(project.provider(() ->
            shadowRuntimeConfiguration.resolve()
                .stream()
                .map(it->it.isFile() ? project.zipTree(it) : it)
                .collect(Collectors.toSet())
        ))
    ));
  }
}
