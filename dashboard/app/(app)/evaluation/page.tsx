import { EvaluationTable } from "@/components/EvaluationTable";
import { Empty, Section } from "@/components/Section";
import { loadReport } from "@/lib/evaluation";

export const dynamic = "force-dynamic";
export const metadata = { title: "Evaluation" };

export default async function EvaluationPage() {
  const report = await loadReport();
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">Evaluation</h1>
      <Section title="Fixture results">
        {report ? (
          <EvaluationTable report={report} />
        ) : (
          <Empty>No evaluation has been run. Generate fixtures and run the evaluation lab to fill this page.</Empty>
        )}
      </Section>
    </div>
  );
}
