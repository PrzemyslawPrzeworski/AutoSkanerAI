import { AnalysisRequest, ExtractedData } from './analysis.models';

/**
 * What the user can type about the car, as strings — numeric fields included, because an
 * `<input>` holds text and a half-typed "202" is not a year yet. Conversion happens once, in
 * {@link draftToRequest}, so no template has to care.
 */
export interface VehicleDataDraft {
  vin: string;
  registrationPlate: string;
  firstRegistrationDate: string;
  make: string;
  model: string;
  year: string;
  priceAmount: string;
  mileageKm: string;
  fuel: string;
  transmission: string;
  notes: string;
}

export function emptyDraft(): VehicleDataDraft {
  return {
    vin: '',
    registrationPlate: '',
    firstRegistrationDate: '',
    make: '',
    model: '',
    year: '',
    priceAmount: '',
    mileageKm: '',
    fuel: '',
    transmission: '',
    notes: '',
  };
}

/**
 * historiapojazdu.gov.pl needs all three of VIN + plate + first registration date. The user should
 * only ever have to type the VIN, though: the other two are published in the advert and arrive via
 * {@link prefillFromExtracted}. So this names the fields that are *actually* still missing rather
 * than demanding the user fill a form they already filled.
 */
export function missingRegistryFields(draft: VehicleDataDraft): string[] {
  const missing: string[] = [];
  if (!draft.vin.trim()) missing.push('numer VIN');
  if (!draft.registrationPlate.trim()) missing.push('numer rejestracyjny');
  if (!draft.firstRegistrationDate.trim()) missing.push('data pierwszej rejestracji');
  return missing;
}

export function isDraftEmpty(draft: VehicleDataDraft): boolean {
  return Object.values(draft).every((value) => !value.trim());
}

/**
 * Prefills the registry fields from what the LLM read, so a user only has to type what is
 * actually missing. Never overwrites something the user already typed — the draft is theirs.
 */
export function prefillFromExtracted(
  draft: VehicleDataDraft,
  extracted: ExtractedData | null | undefined,
): VehicleDataDraft {
  if (!extracted) return draft;
  return {
    ...draft,
    vin: draft.vin.trim() || extracted.vin || '',
    registrationPlate: draft.registrationPlate.trim() || extracted.registrationPlate || '',
    firstRegistrationDate:
      draft.firstRegistrationDate.trim() || extracted.firstRegistrationDate || '',
  };
}

// 17 characters, and I, O and Q are not VIN letters — they are excluded by ISO 3779 precisely
// because they are mistaken for 1 and 0. The same rule the backend applies; checking it here too
// saves the user a ~30 s analysis that could only ever come back MISSING_INPUTS.
const VIN_PATTERN = /^[A-HJ-NPR-Z0-9]{17}$/;

export function normaliseVin(raw: string): string {
  return raw.trim().toUpperCase().replace(/[\s-]/g, '');
}

export function vinError(raw: string): string | null {
  const value = normaliseVin(raw);
  if (!value) return null;
  if (value.length !== 17) {
    return `VIN ma 17 znaków (wpisano ${value.length}).`;
  }
  if (!VIN_PATTERN.test(value)) {
    return 'VIN nie może zawierać liter I, O ani Q — sprawdź, czy to nie 1 lub 0.';
  }
  return null;
}

/**
 * Merges the draft into a request. Blank fields are omitted rather than sent as empty strings:
 * the backend treats a blank override as "no opinion" either way, but sending nothing keeps the
 * payload honest about what the user actually said.
 */
export function draftToRequest(
  base: Pick<AnalysisRequest, 'url' | 'listingText'>,
  draft: VehicleDataDraft,
): AnalysisRequest {
  const manual = {
    make: text(draft.make),
    model: text(draft.model),
    year: integer(draft.year),
    priceAmount: integer(draft.priceAmount),
    mileageKm: integer(draft.mileageKm),
    fuel: text(draft.fuel),
    transmission: text(draft.transmission),
    notes: text(draft.notes),
  };
  const hasManual = Object.values(manual).some((value) => value !== undefined);

  return {
    ...base,
    manual: hasManual ? manual : undefined,
    vin: draft.vin.trim() ? normaliseVin(draft.vin) : undefined,
    registrationPlate: text(draft.registrationPlate)?.toUpperCase(),
    firstRegistrationDate: text(draft.firstRegistrationDate),
  };
}

function text(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

// Digits only, and spaces dropped so "26 320" typed the Polish way is not rejected.
function integer(value: string): number | undefined {
  const digits = value.replace(/[\s ]/g, '');
  if (!digits || !/^\d+$/.test(digits)) return undefined;
  return Number(digits);
}
