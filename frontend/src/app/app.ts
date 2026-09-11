import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/services/auth.service';

// No PrimeNG import here on purpose — see the comment on the logout button in `app.html`. The shell
// is eager, so anything it imports is in the initial bundle for every visitor.
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);

  protected readonly auth = inject(AuthService);

  logout(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }
}
