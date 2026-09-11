/**
 * Hand-written mirror of the Java records in `com.example.autoskaner_ai.auth`.
 *
 * <p>Same arrangement as `analysis.models.ts`, and the same hazard: nothing checks that these names
 * still match the wire. A rename on the Java side ships green here — the backend suite asserts its
 * own JSON and the frontend suite asserts against hand-written doubles. The failure is louder for
 * these types than for the analysis ones, though: a wrong field name in `AuthResponse` means the
 * access token is `undefined`, so every request goes out unauthenticated and the app answers 401 to
 * a user who just logged in successfully.
 */

/** What `/register`, `/login` and `/refresh` all return. */
export interface AuthTokensResponse {
  accessToken: string;
  refreshToken: string;
  /** Access-token lifetime in seconds — 900 at the time of writing. */
  expiresIn: number;
  email: string;
}

/** What `GET /api/auth/me` answers. */
export interface CurrentUser {
  userId: number;
  email: string;
}

/** The one error envelope every endpoint uses; see `backend/CLAUDE.md` § "API error shape". */
export interface ApiErrorBody {
  status: number;
  error: string;
  messages: string[];
  timestamp: string;
}
