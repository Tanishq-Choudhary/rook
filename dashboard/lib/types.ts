export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Installation {
  id: string;
  githubInstallationId: number | null;
  accountLogin: string;
  status: string;
  createdAt: string;
}

export interface Repository {
  id: string;
  installationId: string;
  githubRepoId: number | null;
  fullName: string;
  defaultBranch: string;
  enabled: boolean;
  lastIndexedSha: string | null;
  watches: string[];
}

export interface Source {
  id: string;
  kind: string;
  displayName: string;
  location: string;
  lastVersion: string | null;
  lastCheckedAt: string | null;
  lastError: string | null;
  enabled: boolean;
}

export interface ChangeSetSummary {
  id: string;
  sourceId: string;
  fromVersion: string;
  toVersion: string;
  highestSeverity: string;
  requiresMigration: boolean;
  detectedAt: string;
  events: number;
}

export interface ChangeEventItem {
  fingerprint: string;
  kind: string;
  severity: string;
  subjectKind: string;
  subjectName: string;
  attributes: Record<string, string>;
  summary: string;
}

export interface ChangeSetDetail {
  changeSet: ChangeSetSummary;
  events: ChangeEventItem[];
  notes: { title: string; body: string; url: string }[];
}

export interface JobSummary {
  id: string;
  changeSetId: string;
  repositoryId: string;
  repository: string;
  state: string;
  attempt: number;
  priority: number;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
}

export interface TimelineEntry {
  id: number;
  at: string;
  kind: string;
  state: string | null;
  data: Record<string, unknown>;
}

export interface ImpactLocation {
  file: string;
  line: number;
  column: number;
  snippet: string;
  enclosing: string;
  referenceKind: string;
  confidence: string;
  test: boolean;
}

export interface ImpactEntry {
  fingerprint: string;
  kind: string;
  severity: string;
  subject: string;
  summary: string;
  locations: ImpactLocation[];
}

export interface Impact {
  decision: string;
  summary: string;
  baseSha: string;
  report: { impacts: ImpactEntry[] };
}

export interface TraceStep {
  sequence: number;
  kind: string;
  name: string;
  detail: string;
  millis: number;
  error: boolean;
}

export interface Proposal {
  strategy: string;
  status: string;
  summary: string | null;
  confidence: number | null;
  diff: string;
  changedFiles: string[];
  unresolved: string[];
  costMicros: number;
  trace: TraceStep[];
}

export interface Gate {
  name: string;
  passed: boolean;
  detail: string;
}

export interface Verification {
  passed: boolean;
  supported: boolean;
  summary: string;
  baselineRun: number | null;
  baselineFailed: number | null;
  patchedRun: number | null;
  patchedFailed: number | null;
  elapsedMillis: number;
  report: { gates?: Gate[]; flaky?: string[]; regressions?: string[] };
}

export interface PolicyDecision {
  outcome: string;
  reasons: { code: string; message: string }[];
}

export interface Approval {
  id: string;
  jobId: string;
  status: string;
  reasons: { code: string; message: string }[];
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  note: string | null;
}

export interface PullRequestInfo {
  number: number;
  url: string;
  branch: string;
  state: string;
}

export interface JobDetail {
  job: JobSummary;
  timeline: TimelineEntry[];
  impact: Impact | null;
  proposal: Proposal | null;
  verification: Verification | null;
  policy: PolicyDecision | null;
  approval: Approval | null;
  pullRequest: PullRequestInfo | null;
  spendMicros: number;
}

export interface Usage {
  monthStart: string;
  spentMicros: number;
  reservedMicros: number;
  monthlyCapMicros: number;
  remainingMicros: number;
  modelMicros: number;
  sandboxMicros: number;
}
