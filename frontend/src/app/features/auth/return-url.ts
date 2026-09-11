/**
 * Sanitises the `returnUrl` the guard attaches when it bounces someone to the login form.
 *
 * <p>It is its own module because it is the security-relevant half of the login component and it is
 * worth testing directly: the value is attacker-supplied query text, and navigating to whatever it
 * says turns the login form into an open redirect — a phishing page reached through a link that
 * genuinely starts at this app's domain.
 *
 * <p>Only a same-origin path survives. `//evil.example.com` is rejected specifically: it is a
 * protocol-relative URL, so it starts with a slash and still leaves the site.
 */
export function safeReturnUrl(requested: string | null | undefined): string {
  if (!requested || !requested.startsWith('/') || requested.startsWith('//')) {
    return '/';
  }
  // A backslash is treated as a slash by some URL parsers, so `/\evil.example.com` is the same trick
  // spelled differently.
  if (requested.startsWith('/\\')) {
    return '/';
  }
  return requested;
}
