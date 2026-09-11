import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/register.component').then((m) => m.RegisterComponent),
  },
  {
    path: '',
    // FR-010: the analyser is the whole app, and an unauthenticated visitor gets none of it. The API
    // enforces this on its own (401 on `/api/**`) — the guard is so the user sees a login form
    // instead of a form that fails on submit.
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/analyzer/analyzer.component').then((m) => m.AnalyzerComponent),
  },
  { path: '**', redirectTo: '' },
];
