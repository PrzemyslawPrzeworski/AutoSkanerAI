import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { LoginComponent } from './login.component';
import { AuthService } from '../../core/services/auth.service';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let httpMock: HttpTestingController;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;
  const queryParams: Record<string, string> = {};

  beforeEach(async () => {
    localStorage.clear();
    for (const key of Object.keys(queryParams)) {
      delete queryParams[key];
    }

    // A real router, because the template's routerLink needs one, with only `ActivatedRoute`
    // replaced — that snapshot is where `returnUrl` comes from and there is no navigation here to
    // populate it.
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: { get: (key: string) => queryParams[key] ?? null } },
          },
        },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  function type(id: string, value: string): void {
    const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function submit(): void {
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit'),
    );
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  it('asks for both fields before spending a round trip', () => {
    submit();

    expect(text()).toContain('Podaj e-mail i hasło.');
    httpMock.expectNone('/api/auth/login');
  });

  it('logs in and lands on the app', () => {
    type('login-email', '  Owner@Example.PL  ');
    type('login-password', 'correct-horse');
    submit();

    const req = httpMock.expectOne('/api/auth/login');
    // Trimmed but not lower-cased: the server owns normalisation, and lower-casing here would be a
    // second copy of that rule to drift from.
    expect(req.request.body).toEqual({ email: 'Owner@Example.PL', password: 'correct-horse' });
    req.flush({
      accessToken: 'a',
      refreshToken: 'r',
      expiresIn: 900,
      email: 'owner@example.pl',
    });
    fixture.detectChanges();

    expect(navigateByUrl).toHaveBeenCalledWith('/');
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
  });

  it('returns the user to the page the guard bounced them from', () => {
    queryParams['returnUrl'] = '/analiza/7';

    type('login-email', 'owner@example.pl');
    type('login-password', 'correct-horse');
    submit();
    httpMock
      .expectOne('/api/auth/login')
      .flush({ accessToken: 'a', refreshToken: 'r', expiresIn: 900, email: 'owner@example.pl' });

    expect(navigateByUrl).toHaveBeenCalledWith('/analiza/7');
  });

  /** See `safeReturnUrl` — a login form that navigates wherever the query says is an open redirect. */
  it('ignores a returnUrl pointing off-site', () => {
    queryParams['returnUrl'] = '//evil.example.com';

    type('login-email', 'owner@example.pl');
    type('login-password', 'correct-horse');
    submit();
    httpMock
      .expectOne('/api/auth/login')
      .flush({ accessToken: 'a', refreshToken: 'r', expiresIn: 900, email: 'owner@example.pl' });

    expect(navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('shows the server 401 message and stays put', () => {
    type('login-email', 'owner@example.pl');
    type('login-password', 'wrong');
    submit();

    httpMock.expectOne('/api/auth/login').flush(
      {
        status: 401,
        error: 'Nieprawidłowe dane logowania',
        messages: ['Nieprawidłowy e-mail lub hasło.'],
        timestamp: '',
      },
      { status: 401, statusText: 'Unauthorized' },
    );
    fixture.detectChanges();

    expect(text()).toContain('Nieprawidłowy e-mail lub hasło.');
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  /**
   * A malformed address is not flagged locally: the server answers it with the same 401 as a wrong
   * password, and a client-side "to nie e-mail" would tell a stranger which addresses look registered.
   */
  it('sends a malformed address to the server rather than judging it here', () => {
    type('login-email', 'not-an-email');
    type('login-password', 'correct-horse');
    submit();

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.body).toEqual({ email: 'not-an-email', password: 'correct-horse' });
    req.flush(null, { status: 401, statusText: 'Unauthorized' });
  });

  it('disables the button while the request is in flight', () => {
    type('login-email', 'owner@example.pl');
    type('login-password', 'correct-horse');
    submit();

    const button = fixture.nativeElement.querySelector(
      'button[type="submit"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(text()).toContain('Logowanie…');

    httpMock
      .expectOne('/api/auth/login')
      .flush({ accessToken: 'a', refreshToken: 'r', expiresIn: 900, email: 'owner@example.pl' });
    fixture.detectChanges();

    expect(button.disabled).toBe(false);
  });
});
