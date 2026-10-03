import { percent, type EvaluationReport } from "@/lib/evaluation";
import { formatMicros, words } from "@/lib/format";

export function EvaluationTable({ report }: { report: EvaluationReport }) {
  return (
    <div className="space-y-6">
      <p className="text-sm text-muted">
        {report.fixtures} generated fixtures, checked with {report.verifier}, impact bar {report.minimumConfidence.toLowerCase()}.
      </p>
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Strategy results</caption>
        <thead className="text-xs text-muted">
          <tr>
            <th scope="col" className="pb-2 font-medium">
              Strategy
            </th>
            <th scope="col" className="pb-2 font-medium">
              Fixed
            </th>
            <th scope="col" className="pb-2 font-medium">
              Escaped regressions
            </th>
            <th scope="col" className="pb-2 font-medium">
              Edits that should not happen
            </th>
            <th scope="col" className="pb-2 font-medium">
              Cost per fix
            </th>
            <th scope="col" className="pb-2 font-medium">
              Median time
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {report.strategies.map((strategy) => (
            <tr key={strategy.strategy}>
              <th scope="row" className="py-2 font-medium">
                {strategy.strategy}
              </th>
              <td className="py-2">
                {percent(strategy.patchSuccessRate)} ({strategy.accepted} of {strategy.fixtures})
              </td>
              <td className="py-2">{strategy.regressionEscapes}</td>
              <td className="py-2">{strategy.noopFixturesEdited + strategy.protectedFilesEdited}</td>
              <td className="py-2">{formatMicros(strategy.costPerAcceptedMicros)}</td>
              <td className="py-2">{strategy.latencyP50Millis} ms</td>
            </tr>
          ))}
        </tbody>
      </table>
      {report.strategies.map((strategy) => (
        <div key={strategy.strategy}>
          <h3 className="mb-2 text-sm font-medium">{strategy.strategy} by kind of change</h3>
          <ul className="grid gap-1 text-sm sm:grid-cols-2">
            {Object.entries(strategy.categories).map(([name, counts]) => (
              <li key={name} className="flex justify-between border-b border-line py-1">
                <span>{words(name.replace(/-/g, "_"))}</span>
                <span className="text-muted">
                  {counts.accepted} of {counts.total}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-muted">
            Impact precision {strategy.impactPrecision.toFixed(3)}, recall {strategy.impactRecall.toFixed(3)}.
          </p>
        </div>
      ))}
      {report.notRun.length > 0 ? (
        <p className="text-sm text-muted">Not run: {report.notRun.join(", ")}. They need a model and no key was available.</p>
      ) : null}
      <div>
        <h3 className="mb-1 text-sm font-medium">Limits of these numbers</h3>
        <ul className="list-disc space-y-1 pl-5 text-sm text-muted">
          {report.limitations.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}
