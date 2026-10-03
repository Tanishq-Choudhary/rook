import { createServer } from "node:http";

const PORT = Number(process.env.MOCK_PORT ?? 4010);
const KEY = "test-key";
const JOB = "11111111-1111-4111-8111-111111111111";
const FAILED = "22222222-2222-4222-8222-222222222222";
const INSTALLATION = "33333333-3333-4333-8333-333333333333";
const CHANGE = "44444444-4444-4444-8444-444444444444";
const REPOSITORY = "55555555-5555-4555-8555-555555555555";

const state = {
  jobs: new Map([
    [JOB, { state: "AWAITING_APPROVAL", repository: "acme/shop", error: null }],
    [FAILED, { state: "FAILED", repository: "acme/blog", error: "no patch could be produced" }],
  ]),
  approvals: [],
};

function summary(id) {
  const job = state.jobs.get(id);
  return {
    id,
    changeSetId: CHANGE,
    repositoryId: REPOSITORY,
    repository: job.repository,
    state: job.state,
    attempt: 1,
    priority: 0,
    lastError: job.error,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:05:00Z",
    finishedAt: null,
  };
}

const DIFF = [
  "--- a/src/main/java/com/shop/Service.java",
  "+++ b/src/main/java/com/shop/Service.java",
  "@@ -8,3 +8,3 @@",
  "     public String go() {",
  "-        return client.charge(5);",
  "+        return client.createCharge(5);",
  "     }",
].join("\n");

function detail(id) {
  const job = state.jobs.get(id);
  const waiting = job.state === "AWAITING_APPROVAL";
  return {
    job: summary(id),
    timeline: [
      { id: 1, at: "2026-10-01T10:00:00Z", kind: "JOB_CREATED", state: "PENDING", data: {} },
      { id: 2, at: "2026-10-01T10:01:00Z", kind: "STEP_STARTED", state: "INDEXING", data: { worker: "worker-1" } },
    ],
    impact: {
      decision: "NEEDS_MIGRATION",
      summary: "1 of 1 changes touch the repository",
      baseSha: "a".repeat(40),
      report: {
        impacts: [
          {
            fingerprint: "f1",
            kind: "METHOD_RENAMED",
            severity: "BREAKING",
            subject: "com.acme.PaymentClient#charge(long)",
            summary: "charge renamed to createCharge",
            locations: [
              {
                file: "src/main/java/com/shop/Service.java",
                line: 9,
                column: 16,
                snippet: "client.charge(5)",
                enclosing: "Service#go",
                referenceKind: "METHOD_CALL",
                confidence: "RESOLVED",
                test: false,
              },
            ],
          },
        ],
      },
    },
    proposal: {
      strategy: "codemod-only",
      status: "COMPLETE",
      summary: null,
      confidence: null,
      diff: DIFF,
      changedFiles: ["src/main/java/com/shop/Service.java"],
      unresolved: [],
      costMicros: 0,
      trace: [],
    },
    verification: {
      passed: true,
      supported: true,
      summary: "all 2 gates passed",
      baselineRun: 12,
      baselineFailed: 0,
      patchedRun: 12,
      patchedFailed: 0,
      elapsedMillis: 4200,
      report: { gates: [{ name: "compiles", passed: true, detail: "" }, { name: "no-new-failures", passed: true, detail: "" }], flaky: [] },
    },
    policy: { outcome: "REQUIRE_APPROVAL", reasons: [{ code: "APPROVAL_REQUIRED", message: "this policy asks for a review of every change" }] },
    approval: waiting
      ? { id: "a1", jobId: id, status: "PENDING", reasons: [], requestedAt: "2026-10-01T10:04:00Z", decidedBy: null, decidedAt: null, note: null }
      : { id: "a1", jobId: id, status: "APPROVED", reasons: [], requestedAt: "2026-10-01T10:04:00Z", decidedBy: "key:reviewer", decidedAt: "2026-10-01T10:06:00Z", note: "looks right" },
    pullRequest: job.state === "PUBLISHING" ? { number: 12, url: "https://github.test/acme/shop/pull/12", branch: "rook/x", state: "OPEN" } : null,
    spendMicros: 0,
  };
}

function page(items, url) {
  const size = Number(url.searchParams.get("size") ?? 25);
  return { items, page: Number(url.searchParams.get("page") ?? 0), size, total: items.length };
}

function send(response, status, body, type = "application/json") {
  response.writeHead(status, { "Content-Type": type });
  response.end(typeof body === "string" ? body : JSON.stringify(body));
}

function problem(response, status, title, detail) {
  send(response, status, { type: `urn:rook:error:${title.toLowerCase()}`, title, status, detail }, "application/problem+json");
}

const server = createServer((request, response) => {
  const url = new URL(request.url, `http://localhost:${PORT}`);
  if (url.pathname === "/__health") {
    return send(response, 200, { ok: true });
  }
  if (request.headers.authorization !== `Bearer ${KEY}`) {
    return problem(response, 401, "UNAUTHORIZED", "an API key is required");
  }
  const path = url.pathname;
  if (path === "/api/v1/installations") {
    return send(response, 200, [
      { id: INSTALLATION, githubInstallationId: 1, accountLogin: "acme", status: "ACTIVE", createdAt: "2026-09-01T10:00:00Z" },
    ]);
  }
  if (path === "/api/v1/jobs") {
    const wanted = url.searchParams.get("state");
    const items = [...state.jobs.keys()].map(summary).filter((job) => !wanted || job.state === wanted);
    return send(response, 200, page(items, url));
  }
  const approve = /^\/api\/v1\/jobs\/([^/]+)\/(approve|reject|cancel|retry)$/.exec(path);
  if (approve && request.method === "POST") {
    const job = state.jobs.get(approve[1]);
    if (!job) {
      return problem(response, 404, "NOT_FOUND", "job not found");
    }
    if (approve[2] === "approve" && job.state === "AWAITING_APPROVAL") {
      job.state = "PUBLISHING";
      return send(response, 200, { result: "APPLIED" });
    }
    if (approve[2] === "reject" && job.state === "AWAITING_APPROVAL") {
      job.state = "REJECTED";
      return send(response, 200, { result: "APPLIED" });
    }
    if (approve[2] === "retry" && job.state === "FAILED") {
      job.state = "PENDING";
      job.error = null;
      return send(response, 200, { result: "REQUEUED" });
    }
    return problem(response, 409, "CONFLICT", "the job has no pending approval");
  }
  const one = /^\/api\/v1\/jobs\/([^/]+)$/.exec(path);
  if (one) {
    return state.jobs.has(one[1]) ? send(response, 200, detail(one[1])) : problem(response, 404, "NOT_FOUND", "job not found");
  }
  if (path === "/api/v1/sources") {
    return send(response, 200, [
      { id: "acme-sdk", kind: "MAVEN", displayName: "Acme SDK", location: "https://repo.example.test", lastVersion: "2.0.0", lastCheckedAt: "2026-10-01T09:00:00Z", lastError: null, enabled: true },
    ]);
  }
  if (path === "/api/v1/change-sets") {
    return send(response, 200, page([{ id: CHANGE, sourceId: "acme-sdk", fromVersion: "1.0.0", toVersion: "2.0.0", highestSeverity: "BREAKING", requiresMigration: true, detectedAt: "2026-10-01T09:00:00Z", events: 2 }], url));
  }
  if (path === `/api/v1/change-sets/${CHANGE}`) {
    return send(response, 200, {
      changeSet: { id: CHANGE, sourceId: "acme-sdk", fromVersion: "1.0.0", toVersion: "2.0.0", highestSeverity: "BREAKING", requiresMigration: true, detectedAt: "2026-10-01T09:00:00Z", events: 2 },
      events: [
        { fingerprint: "e1", kind: "METHOD_RENAMED", severity: "BREAKING", subjectKind: "JAVA_METHOD", subjectName: "PaymentClient#charge(long)", attributes: {}, summary: "charge renamed to createCharge" },
        { fingerprint: "e2", kind: "METHOD_ADDED", severity: "ADDITIVE", subjectKind: "JAVA_METHOD", subjectName: "PaymentClient#refund(long)", attributes: {}, summary: "refund was added" },
      ],
      notes: [{ title: "Upgrade guide", body: "Use createCharge.", url: "" }],
    });
  }
  if (path === "/api/v1/repositories") {
    return send(response, 200, page([{ id: REPOSITORY, installationId: INSTALLATION, githubRepoId: 10, fullName: "acme/shop", defaultBranch: "main", enabled: true, lastIndexedSha: "b".repeat(40), watches: ["acme-sdk"] }], url));
  }
  if (path === "/api/v1/usage") {
    return send(response, 200, { monthStart: "2026-10-01T00:00:00Z", spentMicros: 1_200_000, reservedMicros: 0, monthlyCapMicros: 50_000_000, remainingMicros: 48_800_000, modelMicros: 1_000_000, sandboxMicros: 200_000 });
  }
  if (path === "/api/v1/policies/effective") {
    return send(response, 200, {
      version: 0,
      policy: { maxFilesChanged: 10, maxChangedLines: 400, allowedPaths: [], deniedPaths: [".github/workflows/**"], allowBuildFileChanges: false, requireVerified: true, approval: "THRESHOLD", approvalAboveFiles: 3, approvalAboveLines: 80, approvalBelowAgentConfidence: 0.7, approvalForTextualMatches: true, maxJobCostMicros: 2000000 },
    });
  }
  if (path === "/api/v1/audit") {
    return send(response, 200, [{ seq: 1, at: "2026-10-01T10:00:00Z", actor: "rook", action: "IMPACT_ANALYZED", subject: `job:${JOB}`, data: {}, hash: "c".repeat(64) }]);
  }
  if (path === "/api/v1/audit/verify") {
    return send(response, 200, { intact: true, checked: 1, firstBadSeq: null, problem: "", head: "c".repeat(64) });
  }
  if (path === "/api/v1/events") {
    response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
    response.write(": connected\n\n");
    const timer = setTimeout(() => {
      const event = { id: 99, jobId: JOB, at: new Date().toISOString(), kind: "PATCH_PROPOSED", state: "PROPOSING", data: {} };
      response.write(`id: 99\nevent: job\ndata: ${JSON.stringify(event)}\n\n`);
    }, 400);
    request.on("close", () => clearTimeout(timer));
    return undefined;
  }
  return problem(response, 404, "NOT_FOUND", "no such endpoint");
});

server.listen(PORT, "127.0.0.1", () => {
  process.stdout.write(`mock api on ${PORT}\n`);
});
