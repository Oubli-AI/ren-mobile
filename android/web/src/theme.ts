/*
 * ren's theme on the app's pages: the one chosen in the app's Settings (Light, Dark, Glass or System), applied with
 * ren desktop's own classes (`applyThemeClasses`: `dark`, and `glass` for Glass, whose painted backdrop is ren's own
 * look away from macOS). System and Glass follow the phone's dark mode. Until the app answers, the last choice the
 * pages kept (ren keeps it in localStorage as `theme`) stands.
 */
import { theme } from "./host"
import { THEME_OPTIONS, applyThemeClasses, type ThemeName } from "@/lib/theme"

function kept(): ThemeName {
  const stored = localStorage.getItem("theme")
  return THEME_OPTIONS.find((it) => it.id === stored)?.id ?? "system"
}

export function applyTheme() {
  let current = kept()
  applyThemeClasses(current)
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => applyThemeClasses(current))
  void theme().then((chosen) => {
    if (chosen === null) return
    current = chosen
    applyThemeClasses(chosen)
  })
}
