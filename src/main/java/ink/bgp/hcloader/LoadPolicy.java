package ink.bgp.hcloader;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
/* package-private */ enum LoadPolicy {
  PARENT_FIRST(false, true, true),
  SELF_FIRST(true, true, false),
  PARENT_ONLY(false, true, false),
  SELF_ONLY(true, false, false),
  FORBIDDEN(false, false, false);

  private final boolean selfFirst;
  private final boolean parentSecond;
  private final boolean selfThird;

  public boolean selfEnabled() {
    return selfFirst || selfThird;
  }
}
