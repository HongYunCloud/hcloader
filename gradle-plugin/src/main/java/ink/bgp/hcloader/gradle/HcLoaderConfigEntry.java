package ink.bgp.hcloader.gradle;

import lombok.Data;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

@Data(staticConstructor = "of")
public final class HcLoaderConfigEntry implements Serializable {
  private final int priority;
  private final @NotNull String globPattern;
  private final @NotNull HcLoaderLoadPolicy policy;

  public enum HcLoaderLoadPolicy {
    PARENT_FIRST,
    SELF_FIRST,
    PARENT_ONLY,
    SELF_ONLY,
    FORBIDDEN;
  }
}
