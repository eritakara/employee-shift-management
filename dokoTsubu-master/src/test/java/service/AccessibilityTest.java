package service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AccessibilityTest {
  public static void main(String[] args) throws Exception {
    Path web = Path.of("src/main/webapp");
    List<Path> pages = List.of(
        web.resolve("index.jsp"),
        web.resolve("WEB-INF/jsp/app.jsp"),
        web.resolve("WEB-INF/jsp/forgot.jsp"),
        web.resolve("WEB-INF/jsp/reset.jsp"),
        web.resolve("WEB-INF/jsp/privacy.jsp"));
    for (Path page : pages) {
      String source = Files.readString(page);
      check(source.contains("class=\"skip-link\""), page + " has a skip link");
      check(source.contains("id=\"main-content\""), page + " has a main-content target");
      check(source.contains("<html lang="), page + " declares page language");
      check(source.contains("name=\"viewport\""), page + " supports responsive zoom");
      check(!source.contains("onclick="), page + " avoids mouse-only inline handlers");
    }

    String app = Files.readString(web.resolve("WEB-INF/jsp/app.jsp"));
    check(app.contains("aria-controls=\"main-navigation\""), "mobile menu identifies its controlled navigation");
    check(app.contains("aria-expanded=\"false\""), "mobile menu exposes expanded state");
    String css = Files.readString(web.resolve("assets/app.css"));
    check(css.contains(":focus-visible"), "keyboard focus has a visible style");
    check(css.contains("min-height: 40px"), "common controls meet minimum target height");
    check(css.contains(".clock-actions button { min-width: 150px; min-height: 54px; }"),
        "time-clock actions use enlarged targets");
    String script = Files.readString(web.resolve("assets/app.js"));
    check(script.contains("event.key === 'Escape'"), "mobile navigation closes with Escape");
    check(script.contains("aria-expanded"), "mobile navigation updates accessibility state");

    check(contrast("#ffffff", "#087f78") >= 4.5, "primary button contrast");
    check(contrast("#17202a", "#ffffff") >= 4.5, "body text contrast");
    check(contrast("#65717d", "#ffffff") >= 4.5, "muted text contrast");
    check(contrast("#c23b3b", "#ffffff") >= 4.5, "error text contrast");
    System.out.println("AccessibilityTest: all checks passed");
  }

  private static double contrast(String first, String second) {
    double light = luminance(first);
    double dark = luminance(second);
    if (dark > light) { double swap = light; light = dark; dark = swap; }
    return (light + 0.05) / (dark + 0.05);
  }

  private static double luminance(String color) {
    int rgb = Integer.parseInt(color.substring(1), 16);
    double red = channel((rgb >> 16) & 255);
    double green = channel((rgb >> 8) & 255);
    double blue = channel(rgb & 255);
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
  }

  private static double channel(int value) {
    double normalized = value / 255.0;
    return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}

