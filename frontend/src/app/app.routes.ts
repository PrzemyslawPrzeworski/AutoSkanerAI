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
    // FR-011/FR-012. Listed before `''` only for readability — the router matches on path, not order,
    // for non-empty paths. Both saved routes carry `authGuard`: the rows belong to an account, and
    // without it a reload of `/saved` would fire the list request with no access token in memory.
    path: 'saved',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/saved/saved-analyses.component').then((m) => m.SavedAnalysesComponent),
  },
  {
    path: 'saved/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/saved/saved-analysis-detail.component').then(
        (m) => m.SavedAnalysisDetailComponent,
      ),
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
