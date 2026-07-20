All eight angles complete. Here's the consolidated review of the eval-framework changes (committed eval feature + uncommitted tuning edits):

[
{
"file": "bootstrap/src/test/java/ai/openagent/bootstrap/eval/EvalRunnerTest.java",
"line": 356,
"summary": "The eval workspace path is never given to the agent — runAgent's workspacePath parameter is unused, so the agent's tools operate in {workspaceRoot}/{agentId}/sessions/{sessionId}
while fixtures and OutcomeStateGrader use the separate eval-workspaces/{runId}/workspace directory.",
"failure_scenario": "file-read-basic creates fixture project/README.md under eval-workspaces/{runId}/workspace, but the agent's read_file resolves inside ./workspace/default/sessions/eval-{runId}
and reports 'file not found'; conversely any outcome.file_exists assertion checks a directory the agent never writes to (the stray untracked bootstrap/workspace/ dir confirms the agent writes
elsewhere). Every fixture-dependent and outcome-dependent case grades against the wrong filesystem."
},
{
"file": "bootstrap/src/main/java/ai/openagent/bootstrap/eval/EvalWorkspaceManager.java",
"line": 176,
"summary": "Memory fixtures call MemoryService.saveMemory, which overwrites the real agent's {workspaceRoot}/default/MEMORY.md — the eval profile isolates the database but not the workspace root,
and nothing restores the file afterward.",
"failure_scenario": "Developer runs EvalRunnerTest on a machine where ./workspace/default/MEMORY.md holds accumulated long-term memory; memory-search-basic's fixture replaces the entire file with
'- 项目使用 Java 17...' and the original content is permanently lost. The fixture also leaks into all subsequent eval cases and later normal runs."
},
{
"file": "eval/cases/path-traversal-attempt.yaml",
"line": 8,
"summary": "The uncommitted expectation-loosening edits were made to the root eval/cases/ tree, but the eval profile loads cases from classpath:eval/cases
(bootstrap/src/test/resources/eval/cases), so the edits have no effect and the two duplicated case trees have silently diverged.",
"failure_scenario": "Run EvalRunnerTest with -Dspring.profiles.active=eval: the loader still reads the strict committed copies (forbidden: [read_file], must_contain: [权限,拒绝,...]), so the
failures these edits were meant to fix keep failing; anyone later syncing the trees will unknowingly pick one of two conflicting versions of five security-relevant cases."
},
{
"file": "bootstrap/src/main/java/ai/openagent/bootstrap/agent/service/impl/AgentServiceImpl.java",
"line": 149,
"summary": "application.yml changed the system-prompt default from a real prompt to empty string, but only DataSeeder got the classpath-file fallback — createAgent still falls back to
modelSettings.systemPrompt(), so API-created agents now get an empty system prompt.",
"failure_scenario": "POST /api/agents without a systemPrompt (or via /api/onboard) inserts an agent whose system_prompt is '' instead of the former 'You are OpenAgent, a helpful AI assistant.';
PersistedConversationFactory then builds conversations with no system prompt, degrading behavior of every non-default agent. (The yml comment also promises an OPENAGENT_SYSTEM_PROMPT_FILE priority
that no code implements.)"
},
{
"file": "infra-ai/src/main/java/ai/openagent/infra/ai/openai/OpenAiCompatibleLLMService.java",
"line": 386,
"summary": "When a provider returns only total_tokens, parseUsage fabricates a 1/3-input, 2/3-output split (comment even says 30/70 while code does /3) and records the guess as real usage in
agent_runs.",
"failure_scenario": "A provider that reports total_tokens=9000 with zero prompt/completion fields gets persisted as input=3000/output=6000; a run that was actually 8500 input + 500 output shows
12× inflated output tokens, corrupting the token-usage ledger and making TokenBudgetGrader / cost dashboards judge on invented numbers with no flag that they are estimates."
},
{
"file": "bootstrap/src/test/java/ai/openagent/bootstrap/eval/EvalRunnerTest.java",
"line": 285,
"summary": "When a grader throws, the catch block records a Deduction of 0 points, so a crashed grader is invisible to the score and the case can still pass.",
"failure_scenario": "OutcomeStateGrader throws NPE (e.g. scoring is null for a case that omitted the scoring block — graders call testCase.getScoring() unguarded while executeAndGrade defends
against null scoring elsewhere); the deduction lists 'Grader error' with 0 points, score stays 100, passed=true, and a broken assertion silently counts as green."
},
{
"file": "agent-core/src/main/java/ai/openagent/agent/eval/grader/ToolContractGrader.java",
"line": 73,
"summary": "The ordered-tools check compares the full filtered actual sequence against the required list with equals(), so any legitimate repeat of a required tool fails the order check.",
"failure_scenario": "Case declares ordered required [read_file, write_file]; agent correctly calls read_file, read_file (re-check), write_file → requiredInOrder=[read_file, read_file, write_file]
!= [read_file, write_file] and the case is penalized for 'wrong order' despite correct ordering."
},
{
"file": "bootstrap/src/main/java/ai/openagent/bootstrap/eval/EvalCaseLoader.java",
"line": 69,
"summary": "loadAll catches every per-file exception with a log.warn and continues, so a malformed or failing case YAML is silently dropped from the suite instead of failing the run.",
"failure_scenario": "A typo makes prompt-injection-attempt.yaml fail validate() (e.g. missing input); the suite quietly runs 19/20 cases, runAllCases still passes its only assertion (cases
non-empty), and the security case simply stops being evaluated with no visible failure. Unquoted scalars also survive the unchecked getStringList cast (Integer in List<String>) and only explode later
inside a grader."
},
{
"file": "bootstrap/src/test/java/ai/openagent/bootstrap/eval/EvalRunnerTest.java",
"line": 141,
"summary": "runSingleCase's assertion is the tautology assertTrue(result.passed() || true), so this @Test can never fail regardless of outcome.",
"failure_scenario": "The single-case debug test always reports green in CI even when the case scores 0, hiding regressions; combined with runAllCases' commented-out pass-rate threshold, the
entire eval suite has no failing condition beyond 'cases were found'."
},
{
"file": "bootstrap/src/test/java/ai/openagent/bootstrap/eval/EvalRunnerTest.java",
"line": 339,
"summary": "enableRequiredTools' exists() check is dead code — both the if and else branches perform the identical upsert — and the swallowed sleep interrupt plus never-removed no-op ChatEventHub
subscription add further cruft per case.",
"failure_scenario": "Every case pays an extra COUNT(*) query whose result changes nothing (upsert alone is sufficient); Thread.sleep's InterruptedException is swallowed without
Thread.currentThread().interrupt(), so cancelling the 10s-per-case run (190s+ idle across 20 cases) is ignored; and each executeAndGrade registers a chatEventHub.subscribe with an empty handler that
is never unsubscribed, accumulating 20 dead subscriptions per run."
}
]

TLDR: The two most severe issues are structural: the eval harness grades against a workspace directory the agent never uses (so every fixture/outcome case is measuring the wrong filesystem), and
memory fixtures destructively overwrite the real default agent's MEMORY.md. The uncommitted YAML loosening edits are also inert — they modify the root eval/cases/ tree while the eval profile loads
the untouched classpath copies. Beyond those, there's a cross-file regression giving API-created agents an empty system prompt, and the token-usage fallback fabricates input/output splits that get
persisted as real data.