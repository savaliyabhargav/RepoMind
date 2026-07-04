// ─── Mermaid sanitizer ───────────────────────────────────────────────────────
// Models often emit subtly broken Mermaid. Fix the most common mistakes before
// passing to mermaid.render() so diagrams render instead of showing raw code.

// A line that starts a valid sequenceDiagram statement. Anything else is treated
// as a continuation of the previous line (models love breaking Note text with
// real newlines, which Mermaid's parser rejects outright).
const SEQ_STATEMENT = new RegExp(
  "^\\s*(" +
  "sequenceDiagram\\b|participant\\b|actor\\b|autonumber\\b|activate\\b|deactivate\\b|" +
  "Note\\b|note\\b|loop\\b|alt\\b|else\\b|opt\\b|par\\b|and\\b|rect\\b|end\\b|break\\b|" +
  "critical\\b|option\\b|box\\b|links\\b|link\\b|title\\b|%%|" +
  "[A-Za-z0-9_]+\\s*(-{1,2}>>?|-{1,2}[xX)])" +
  ")"
);

export function fixMermaidCode(raw) {
  if (!raw) return raw;
  let s = raw
    // Some models double-escape newlines: literal \n → actual newline
    .replace(/\\n/g, "\n")
    // Strip markdown fences that slipped through JSON cleanup
    .replace(/^```(?:mermaid)?\s*/m, "")
    .replace(/\n?```\s*$/m, "")
    .trim();

  // classDiagram: GPT-style models often use colon syntax which Mermaid rejects.
  // Wrong: +fieldName: Type  →  Correct: +fieldName Type
  // Wrong: +method(p: T): R  →  Correct: +method(p T) R
  if (/classDiagram/.test(s)) {
    s = s
      // Fix method param colons: (param: Type, p2: T) → (param Type, p2 T)
      .replace(/\(([^)]*)\)/g, (_, params) =>
        "(" + params.replace(/(\w[\w<>[\]*?]*)\s*:\s*/g, "$1 ") + ")"
      )
      // Fix method return colon: +method(...): ReturnType → +method(...) ReturnType
      .replace(/(\))\s*:\s*(\S)/g, "$1 $2")
      // Fix field colon: +fieldName: Type → +fieldName Type (only at line level)
      .replace(/^(\s+[+\-#~][\w<>[\]*?]+)\s*:\s*(.+)$/gm, "$1 $2");
  }

  // sequenceDiagram: merge continuation lines back into the statement they belong
  // to. Example of broken output this repairs:
  //   Note over X: repoId UUID
  //   fileId UUID          ← not a statement → joined onto the Note line
  if (/^\s*sequenceDiagram/.test(s)) {
    const merged = [];
    for (const line of s.split("\n")) {
      if (line.trim() === "") continue;
      if (merged.length === 0 || SEQ_STATEMENT.test(line)) {
        merged.push(line);
      } else {
        merged[merged.length - 1] += ", " + line.trim();
      }
    }
    s = merged.join("\n");
  }

  return s;
}
