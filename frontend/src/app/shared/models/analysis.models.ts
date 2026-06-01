export type VerdictCode = 'WORTH_CHECKING' | 'NEEDS_MORE_INFO' | 'HIGH_RISK_SKIP';

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
  fetchStatus: 'ok' | 'url_failed' | 'text';
  fetchFailureReason: string | null;
  analysis: AnalysisResult | null;
}

export interface AnalysisRequest {
  url?: string;
  listingText?: string;
}
