"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";

export type Theme = "dark" | "light" | "system";

const STORAGE_KEY = "openagent-theme";

const ThemeContext = createContext<{
  theme: Theme;
  setTheme: (t: Theme) => void;
  toggleTheme: () => void;
  resolvedTheme: "dark" | "light";
}>({
  theme: "dark",
  setTheme: () => {},
  toggleTheme: () => {},
  resolvedTheme: "dark",
});

export function useTheme() {
  return useContext(ThemeContext);
}

function readSystem(): "dark" | "light" {
  if (typeof window === "undefined") return "dark";
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function apply(resolved: "dark" | "light") {
  document.documentElement.classList.toggle("dark", resolved === "dark");
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<Theme>("dark");
  const [resolvedTheme, setResolvedTheme] = useState<"dark" | "light">("dark");

  useEffect(() => {
    // Hydrate from localStorage once on mount. setState here is
    // appropriate — localStorage isn't accessible on the server (so a
    // useState lazy initializer would crash SSR) and we want a single
    // shift to the persisted theme on first paint.
    const stored = localStorage.getItem(STORAGE_KEY) as Theme | null;
    const initial: Theme = stored === "light" || stored === "dark" || stored === "system" ? stored : "dark";
    setThemeState(initial);
    const resolved = initial === "system" ? readSystem() : initial;
    setResolvedTheme(resolved);
    apply(resolved);
  }, []);

  // When theme=system, follow OS changes live so the user doesn't need
  // to reload to pick up sunset/sunrise on macOS auto theme.
  useEffect(() => {
    if (theme !== "system" || typeof window === "undefined") return;
    const mql = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      const next = mql.matches ? "dark" : "light";
      setResolvedTheme(next);
      apply(next);
    };
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    setThemeState(next);
    localStorage.setItem(STORAGE_KEY, next);
    const resolved = next === "system" ? readSystem() : next;
    setResolvedTheme(resolved);
    apply(resolved);
  }, []);

  // toggleTheme is kept for the existing nav-user dropdown — cycles
  // dark → light → dark; "system" can only be selected from /settings.
  const toggleTheme = useCallback(() => {
    setTheme(resolvedTheme === "dark" ? "light" : "dark");
  }, [resolvedTheme, setTheme]);

  return (
    <ThemeContext.Provider value={{ theme, setTheme, toggleTheme, resolvedTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}
