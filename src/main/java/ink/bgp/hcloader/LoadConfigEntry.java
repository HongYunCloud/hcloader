package ink.bgp.hcloader;

import ink.bgp.hcloader.glob.GlobPattern;
import ink.bgp.hcloader.glob.MatchingEngine;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
/* package-private */ final class LoadConfigEntry implements Comparable<LoadConfigEntry> {
  private static final LoadConfigEntry FALLBACK = of(Integer.MIN_VALUE, "**", LoadPolicy.PARENT_FIRST);

  private final int priority;
  private final @NotNull String globPattern;
  private final @NotNull MatchingEngine glob;
  private final @NotNull LoadPolicy policy;

  @Override
  public int compareTo(final @NotNull LoadConfigEntry o) {
    int result = Integer.compare(priority, o.priority);
    if (result != 0) {
      return result;
    }
    result = globPattern.compareTo(o.globPattern);
    if (result != 0) {
      return result;
    }
    return policy.compareTo(o.policy);
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) return true;
    if (object == null || getClass() != object.getClass()) return false;

    LoadConfigEntry that = (LoadConfigEntry) object;

    if (priority != that.priority) return false;
    if (!globPattern.equals(that.globPattern)) return false;
    return policy == that.policy;
  }

  @Override
  public int hashCode() {
    int result = priority;
    result = 31 * result + globPattern.hashCode();
    result = 31 * result + policy.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "LoadConfigEntry{" +
        "priority=" + priority +
        ", globPattern='" + globPattern + '\'' +
        ", policy=" + policy +
        '}';
  }

  public static @NotNull LoadConfigEntry of(
      final int priority,
      final @NotNull String glob,
      final @NotNull LoadPolicy policy) {
    return new LoadConfigEntry(priority, glob, GlobPattern.compile(glob), policy);
  }

  public static @NotNull LoadConfigEntry fallback() {
    return FALLBACK;
  }

  public static void load(final @NotNull InputStream rawIn, final @NotNull LaunchedURLClassLoader target) throws IOException {
    DataInputStream in = new DataInputStream(rawIn);
    int size = in.readInt();
    for (int i = 0; i < size; i++) {
      final int priority = in.readInt();
      final String glob = in.readUTF();
      final String policyName = in.readUTF();
      final LoadPolicy policy;
      switch (policyName) {
        case "PARENT_FIRST": {
          policy = LoadPolicy.PARENT_FIRST;
          break;
        }
        case "SELF_FIRST": {
          policy = LoadPolicy.SELF_FIRST;
          break;
        }
        case "PARENT_ONLY": {
          policy = LoadPolicy.PARENT_ONLY;
          break;
        }
        case "SELF_ONLY": {
          policy = LoadPolicy.SELF_ONLY;
          break;
        }
        case "FORBIDDEN": {
          policy = LoadPolicy.FORBIDDEN;
          break;
        }
        default: {
          throw new IllegalStateException("unknown load policy " + policyName);
        }
      }
      target.addConfig(of(priority, glob, policy));
    }
  }
}
