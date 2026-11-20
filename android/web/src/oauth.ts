/*
 * ren's social sign-in (Google, GitHub, Microsoft) on the phone, done as ren desktop's Electron does it
 * (`loginWithOAuth` in its authStore): Supabase's PKCE flow with a loopback redirect,
 * `http://127.0.0.1:<port>/auth/callback`, which ren's Supabase project already allows for the desktop. The app
 * listens on that port (Electron's `auth.oauthRedirect`) and opens the provider's page in the phone's own browser
 * through a Custom Tab, since Google refuses sign-in inside an app's WebView. The code comes back to this page, which
 * holds the PKCE verifier, and ren's `handleDeepLinkCallback` exchanges it for the session.
 */
import { oauthRedirect, onOAuthCallback, openOAuth } from "./host"
import { isSupabaseConfigured, supabase } from "@/api/supabase"
import type { OAuthProvider } from "@/pages/types/user"
import { handleDeepLinkCallback, useAuthStore } from "@/stores/authStore"

async function loginWithOAuth(provider: OAuthProvider) {
  useAuthStore.setState({ isLoading: true, error: null })
  if (!isSupabaseConfigured()) {
    useAuthStore.setState({ isLoading: false, error: { code: "CONFIG_ERROR", message: "Supabase is not configured in this build." } })
    return
  }
  try {
    const redirectTo = await oauthRedirect()
    if (redirectTo === null) throw new Error("Social sign-in runs in the Ren app.")
    const { data, error } = await supabase.auth.signInWithOAuth({
      provider: provider === "microsoft" ? "azure" : provider,
      // Supabase's Azure guide: request the email scope so the account has an email.
      options: { redirectTo, skipBrowserRedirect: true, ...(provider === "microsoft" ? { scopes: "email" } : {}) },
    })
    if (error) throw error
    if (!data.url || new URL(data.url).protocol !== "https:") throw new Error("Sign-in pages must use https")
    openOAuth(data.url)
    useAuthStore.setState({ isLoading: false })
  } catch (error) {
    useAuthStore.setState({ isLoading: false, error: { code: "OAUTH_ERROR", message: error instanceof Error ? error.message : String(error) } })
  }
}

/** Puts the phone's social sign-in behind ren's sign-in page's buttons, and takes the provider's answer. */
export function phoneOAuth() {
  useAuthStore.setState({ loginWithOAuth })
  // A failure lands in the store's error, which the sign-in page shows; a success signs in, which closes it.
  onOAuthCallback((url) => {
    void handleDeepLinkCallback(url).catch(() => undefined)
  })
}
