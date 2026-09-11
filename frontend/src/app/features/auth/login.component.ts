import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { InputTextModule } from 'primeng/inputtext';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { AuthService } from '../../core/services/auth.service';
import { authErrorMessage } from './auth-error';
import { safeReturnUrl } from './return-url';

@Component({
  selector: 'app-login',
  imports: [InputTextModule, ButtonModule, MessageModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrl: './auth-form.scss',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly email = signal('');
  readonly password = signal('');
  readonly error = signal<string | null>(null);
  readonly loading = signal(false);

  submit(): void {
    if (this.loading()) {
      return;
    }
    const email = this.email().trim();
    const password = this.password();
    // Checked here only so an obviously empty form does not cost a round trip. Anything about the
    // *shape* of the address is left to the server, which answers a wrong address with the same 401
    // as a wrong password — a client-side "to nie e-mail" would tell a stranger which addresses are
    // shaped like registered ones.
    if (email.length === 0 || password.length === 0) {
      this.error.set('Podaj e-mail i hasło.');
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    this.auth.login(email, password).subscribe({
      next: () => {
        this.loading.set(false);
        void this.router.navigateByUrl(this.returnUrl());
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.error.set(authErrorMessage(err));
      },
    });
  }

  /** Where the guard bounced the user from, if it did; see {@link safeReturnUrl} for the filtering. */
  private returnUrl(): string {
    return safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
  }
}
