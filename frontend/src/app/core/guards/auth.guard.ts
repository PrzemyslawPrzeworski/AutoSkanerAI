import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../services/auth.service';

/**
 * Guards everything that needs an account.
 *
 * <p>It calls {@link AuthService.restoreSession} rather than reading `isAuthenticated`, and that is the
 * whole point of the guard existing at all: the access token lives in memory, so **every page reload
 * of a logged-in user arrives with no access token** and a naive `isAuthenticated` check would bounce
 * them to the login form they had already filled in. `restoreSession` spends the stored refresh token
 * first and only then answers.
 *
 * <p>The redirect carries `returnUrl`, so the bounce is recoverable rather than silently dropping the
 * page the user asked for.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth
    .restoreSession()
    .pipe(
      map(
        (restored) =>
          restored || router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } }),
      ),
    );
};

/**
 * The mirror image, on `/login` and `/register`: an already-authenticated visitor is sent to the app
 * instead of being shown a form that would log them into the account they are already in.
 *
 * <p>It also restores first, for the same reason — after a reload the session is real but invisible,
 * and without the restore a refresh on `/login` would show the form to someone who is logged in.
 */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.restoreSession().pipe(map((restored) => !restored || router.createUrlTree(['/'])));
};
