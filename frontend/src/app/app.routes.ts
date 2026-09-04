import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./features/analyzer/analyzer.component').then((m) => m.AnalyzerComponent),
  },
  { path: '**', redirectTo: '' },
];
