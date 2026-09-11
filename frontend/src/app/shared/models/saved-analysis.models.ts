import { AnalysisResponse } from './analysis.models';

/**
 * One row of `/api/saved-analyses`, mirroring the backend's `SavedAnalysisSummaryResponse`.
 *
 * <p>Every descriptive field is nullable because the extraction it was copied from is allowed to find
 * nothing — a listing with no stated price stores `priceAmount: null`, not 0. The list view must
 * render those as "nie podano" rather than as a number, which is the same rule the analysis panels
 * follow: absence is absence, never a value.
 */
export interface SavedAnalysisSummary {
  id: number;
  title: string;
  note: string | null;
  sourceUrl: string | null;
  make: string | null;
  model: string | null;
  productionYear: number | null;
  priceAmount: number | null;
  priceCurrency: string | null;
  mileageKm: number | null;
  verdictCode: string | null;
  overallScore: number | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * A saved analysis read back whole. `analysis` is the untouched `AnalysisResponse` that was saved, so
 * the detail view renders through the same components as a fresh analysis instead of a second,
 * drifting set.
 */
export interface SavedAnalysisDetail {
  summary: SavedAnalysisSummary;
  analysis: AnalysisResponse;
}

/**
 * There is no `userId` here on purpose, and none may be added: the server takes the owner from the
 * bearer token's subject. A field here would be a field a caller can set.
 */
export interface SaveAnalysisRequest {
  title: string;
  note?: string;
  sourceUrl?: string;
  analysis: AnalysisResponse;
}

/** The only two columns an owner may rewrite — the analysis itself is evidence and stays frozen. */
export interface EditSavedAnalysisRequest {
  title: string;
  note: string | null;
}
