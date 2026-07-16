"use client";

import { redirect } from "next/navigation";

// /settings is a layout shell; the meaningful pages live under
// /settings/general, /settings/account, /settings/runtime. Redirect the
// bare path to General — every visitor can access it (no admin gate).
export default function SettingsIndex() {
  redirect("/settings/general");
}
