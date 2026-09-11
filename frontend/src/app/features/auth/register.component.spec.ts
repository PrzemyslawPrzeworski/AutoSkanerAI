import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RegisterComponent } from './register.component';
import { AuthService } from '../../core/services/auth.service';

describe('RegisterComponent', () => {
  let fixture: ComponentFixture<RegisterComponent>;
  let httpMock: HttpTestingController;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [RegisterComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(RegisterComponent);
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

  function fillAndSubmit(email: string, password: string): void {
    type('register-email', email);
    type('register-password', password);
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit'),
    );
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  it('creates the account and lands on the app', () => {
    fillAndSubmit('  New@Example.PL  ', 'correct-horse');

    const req = httpMock.expectOne('/api/auth/register');
    expect(req.request.body).toEqual({ email: 'New@Example.PL', password: 'correct-horse' });
    req.flush({
      accessToken: 'a',
      refreshToken: 'r',
      expiresIn: 900,
      email: 'new@example.pl',
    });
    fixture.detectChanges();

    expect(navigateByUrl).toHaveBeenCalledWith('/');
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
  });

  /**
   * Unlike the login form, registration *does* judge the address locally — it answers 409 on a taken
   * one anyway, so there is no existence left to protect and a round trip buys nothing.
   */
  it('refuses a malformed address without calling the server', () => {
    fillAndSubmit('not-an-email', 'correct-horse');

    expect(text()).toContain('Podaj poprawny adres e-mail.');
    httpMock.expectNone('/api/auth/register');
  });

  it('refuses a password shorter than eight characters', () => {
    fillAndSubmit('new@example.pl', 'short');

    expect(text()).toContain('Hasło musi mieć od 8 do 72 znaków.');
    httpMock.expectNone('/api/auth/register');
  });

  /** BCrypt hashes the first 72 bytes only, so a 73rd character would be silently ignored. */
  it('refuses a password past the BCrypt limit', () => {
    fillAndSubmit('new@example.pl', 'x'.repeat(73));

    expect(text()).toContain('Hasło musi mieć od 8 do 72 znaków.');
    httpMock.expectNone('/api/auth/register');
  });

  it('accepts a password of exactly 72 characters', () => {
    fillAndSubmit('new@example.pl', 'x'.repeat(72));

    const req = httpMock.expectOne('/api/auth/register');
    expect((req.request.body as { password: string }).password.length).toBe(72);
    req.flush({ accessToken: 'a', refreshToken: 'r', expiresIn: 900, email: 'new@example.pl' });
  });

  it('asks for both fields when the form is empty', () => {
    fillAndSubmit('', '');

    expect(text()).toContain('Podaj e-mail i hasło.');
    httpMock.expectNone('/api/auth/register');
  });

  it('shows the server message when the address is already taken', () => {
    fillAndSubmit('taken@example.pl', 'correct-horse');

    httpMock.expectOne('/api/auth/register').flush(
      {
        status: 409,
        error: 'Konto już istnieje',
        messages: ['Konto z tym adresem e-mail już istnieje.'],
        timestamp: '',
      },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();

    expect(text()).toContain('Konto z tym adresem e-mail już istnieje.');
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
  });

  it('falls back to its own wording when the server sends no message', () => {
    fillAndSubmit('taken@example.pl', 'correct-horse');

    httpMock.expectOne('/api/auth/register').flush(null, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(text()).toContain('Konto z tym adresem e-mail już istnieje.');
  });
});
