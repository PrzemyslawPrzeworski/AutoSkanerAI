export type VerdictCode = 'WORTH_CHECKING' | 'NEEDS_MORE_INFO' | 'HIGH_RISK_SKIP';

export type CepikStatus = 'FOUND' | 'NOT_FOUND' | 'LOOKUP_FAILED' | 'MISSING_INPUTS';

export interface MileageStamp {
  date: string;
  mileageKm: number | null;
}
export interface DamageRecord {
  // Nullable: the backend reads this from the registry event's date field, which may be absent.
  date: string | null;
  description: string | null;
  insurer: string | null;
  categories: string[] | null;
}

export interface EventDetail {
  name: string;
  value: string | null;
}
export interface VehicleEvent {
  date: string | null;
  type: string | null;
  name: string | null;
  details: EventDetail[] | null;
}

/**
 * Mirrors the backend record. `null` and `[]` are NOT interchangeable for `damageRecords` and
 * `mileageStamps`: `[]` means the registry timeline was read and held nothing, `null` means
 * nothing was ever checked. Templates must branch on all three states — rendering a `null` as
 * "brak zgłoszonych szkód" is how this app once reported a clean history for a car with a
 * registered szkoda istotna.
 */
export interface CepikResult {
  status: CepikStatus;
  vin: string | null;
  firstRegistrationDatePl: string | null;
  deregisteredDate: string | null;
  originCountry: string | null;
  ownerCount: number | null;
  mileageStamps: MileageStamp[] | null;
  damageRecords: DamageRecord[] | null;
  lookupUrl: string | null;
  fetchedAt: string;
  make: string | null;
  model: string | null;
  vehicleType: string | null;
  yearOfManufacture: number | null;
  registrationStatus: string | null;
  technicalInspectionStatus: string | null;
  ocInsuranceValid: boolean | null;
  vehicleLost: boolean | null;
  odometerRolledBack: boolean | null;
  registrationProvince: string | null;
  events: VehicleEvent[] | null;
}

export type MarketPriceStatus = 'OK' | 'FETCH_FAILED' | 'INSUFFICIENT_DATA' | 'MISSING_INPUTS';

/**
 * How much weight the reported range carries. Decided on the server, next to the thresholds that
 * inform it — the panel only sees a sample size, and `sampleSize < 3` was the wrong test: a sample
 * of exactly 3 is what the pipeline emits when the prices were too far apart to trim, so it was the
 * one size that slipped past the caveat.
 */
export type MarketPriceSampleQuality = 'SUFFICIENT' | 'THIN' | 'DISPERSED';

export interface MarketPriceContext {
  status: MarketPriceStatus;
  minPricePln: number | null;
  medianPricePln: number | null;
  maxPricePln: number | null;
  sampleSize: number | null;
  queryUrl: string | null;
  fetchedAt: string;
  /** Null for every non-`OK` status: there is no sample to judge. */
  sampleQuality: MarketPriceSampleQuality | null;
  /** Prices the server's trim dropped, so the caveat has something to substantiate it. */
  discardedCount: number | null;
}

export interface ExtractedData {
  make: string | null;
  model: string | null;
  year: number | null;
  priceAmount: number | null;
  priceCurrency: string | null;
  mileageKm: number | null;
  fuel: string | null;
  transmission: string | null;
  originCountry: string | null;
  sellerType: string | null;
  serviceHistoryMentioned: boolean | null;
  accidentClaim: string | null;
  vinPresent: boolean | null;
  vin: string | null;
  registrationPlate: string | null;
  firstRegistrationDate: string | null;
}

export interface EquipmentItem {
  name: string;
  status: 'CONFIRMED' | 'MISSING' | 'UNCLEAR';
  note: string | null;
}

export interface RiskFlag {
  code: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  description: string;
}

export interface CategoryScores {
  completeness: number;
  equipment: number;
  risk: number;
  value: number;
  overall: number;
}

export interface Verdict {
  code: VerdictCode;
  label: string;
}

export interface AnalysisMeta {
  provider: string;
  model: string;
  latencyMs: number;
  generatedAt: string;
}

export interface AnalysisResult {
  extracted: ExtractedData;
  equipment: EquipmentItem[];
  riskFlags: RiskFlag[];
  sellerQuestions: string[];
  scores: CategoryScores;
  verdict: Verdict;
  meta: AnalysisMeta;
}

export interface AnalysisResponse {
  fetchStatus: 'ok' | 'url_failed' | 'text' | 'manual';
  fetchFailureReason: string | null;
  analysis: AnalysisResult | null;
  cepikResult: CepikResult | null;
  marketPriceContext: MarketPriceContext | null;
}

/** Structured manual entry — the third input mode, for when neither URL nor paste is practical. */
export interface ManualListing {
  make?: string;
  model?: string;
  year?: number;
  priceAmount?: number;
  priceCurrency?: string;
  mileageKm?: number;
  fuel?: string;
  transmission?: string;
  notes?: string;
}

export interface AnalysisRequest {
  url?: string;
  listingText?: string;
  manual?: ManualListing;
  /**
   * The three registry inputs, typed by the user. They override the LLM extraction, which is the
   * only way a CEPiK lookup can happen for a URL analysis: Otomoto hides the VIN from logged-out
   * fetches, so the model can never read it.
   */
  vin?: string;
  registrationPlate?: string;
  firstRegistrationDate?: string;
}
