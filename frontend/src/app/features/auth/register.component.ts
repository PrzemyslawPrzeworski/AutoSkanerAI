import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { InputTextModule } from 'primeng/inputtext';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { AuthService } from '../../core/services/auth.service';
import { authErrorMessage } from './auth-error';

/** BCrypt hashes only the first 72 bytes, so the server rejects anything longer; see the backend DTO. */
const MIN_PASSWORD = 8;
const MAX_PASSWORD = 72;

@Component({
  selector: 'app-register',
  imports: [InputTextModule, ButtonModule, MessageModule, RouterLink],
  templateUrl: './register.component.html',
  styleUrl: './auth-form.scss',
})
export class RegisterComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

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
    // Unlike login, the shape *is* checked here: registration answers 409 on a taken address, so it
    // already reveals existence and there is nothing left to protect by staying vague. Telling the
    // user their password is too short costs no round trip.
    const complaint = this.validate(email, password);
    if (complaint !== null) {
      this.error.set(complaint);
      return;
    }

    this.loading.set(true);
    this.error.set(null);
    this.auth.register(email, password).subscribe({
      next: () => {
        this.loading.set(false);
        void this.router.navigateByUrl('/');
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.error.set(authErrorMessage(err));
      },
    });
  }

  private validate(email: string, password: string): string | null {
    if (email.length === 0 || password.length === 0) {
      return 'Podaj e-mail i hasło.';
    }
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
      return 'Podaj poprawny adres e-mail.';
    }
    if (password.length < MIN_PASSWORD || password.length > MAX_PASSWORD) {
      return `Hasło musi mieć od ${MIN_PASSWORD} do ${MAX_PASSWORD} znaków.`;
    }
    return null;
  }
}
