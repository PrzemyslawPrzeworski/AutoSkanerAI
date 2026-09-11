import { safeReturnUrl } from './return-url';

/**
 * `returnUrl` is attacker-supplied text arriving on a link that genuinely starts at this app's domain,
 * so every case below is a phishing redirect the login form must not perform. Tested directly rather
 * than only through the component because that is where the property is decidable.
 */
describe('safeReturnUrl', () => {
  it('keeps a same-origin path', () => {
    expect(safeReturnUrl('/')).toBe('/');
    expect(safeReturnUrl('/analiza/12')).toBe('/analiza/12');
    expect(safeReturnUrl('/?url=https://otomoto.pl/x')).toBe('/?url=https://otomoto.pl/x');
  });

  it('falls back to the root when there is nothing to return to', () => {
    expect(safeReturnUrl(null)).toBe('/');
    expect(safeReturnUrl(undefined)).toBe('/');
    expect(safeReturnUrl('')).toBe('/');
  });

  it('refuses an absolute URL', () => {
    expect(safeReturnUrl('https://evil.example.com')).toBe('/');
    expect(safeReturnUrl('http://evil.example.com/login')).toBe('/');
    expect(safeReturnUrl('javascript:alert(1)')).toBe('/');
  });

  /** Starts with a slash and still leaves the site — the case a naive `startsWith('/')` check misses. */
  it('refuses a protocol-relative URL', () => {
    expect(safeReturnUrl('//evil.example.com')).toBe('/');
    expect(safeReturnUrl('//evil.example.com/path')).toBe('/');
  });

  it('refuses the backslash spelling of the same trick', () => {
    expect(safeReturnUrl('/\\evil.example.com')).toBe('/');
  });
});
