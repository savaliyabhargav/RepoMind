import { describe, it, expect } from 'vitest';
import { fixMermaidCode } from './fixMermaidCode';

describe('fixMermaidCode', () => {
  it('merges multi-line Note text back onto one line (real production failure)', () => {
    const broken = `sequenceDiagram
    participant Client
    participant FileExplainController
    Client->>FileExplainController: GET /explain
    Note over FileExplainController: repoId UUID
fileId UUID
aiProvider default NVIDIA_DEV
    FileExplainController-->>Client: 200 OK`;

    const fixed = fixMermaidCode(broken);

    expect(fixed).toContain(
      'Note over FileExplainController: repoId UUID, fileId UUID, aiProvider default NVIDIA_DEV'
    );
    // Statement lines are preserved as-is
    expect(fixed).toContain('Client->>FileExplainController: GET /explain');
    expect(fixed).toContain('FileExplainController-->>Client: 200 OK');
    // No orphan continuation lines remain
    expect(fixed).not.toMatch(/^fileId UUID$/m);
  });

  it('keeps valid sequence diagrams untouched (except blank lines)', () => {
    const valid = `sequenceDiagram
    participant A
    participant B
    A->>B: hello
    B-->>A: world
    Note over A: single line note`;
    expect(fixMermaidCode(valid)).toBe(valid);
  });

  it('preserves control-flow keywords as their own statements', () => {
    const withBlocks = `sequenceDiagram
    participant A
    participant B
    alt success
    A->>B: ok
    else failure
    A->>B: fail
    end`;
    const fixed = fixMermaidCode(withBlocks);
    expect(fixed).toMatch(/^\s*alt success$/m);
    expect(fixed).toMatch(/^\s*else failure$/m);
    expect(fixed).toMatch(/^\s*end$/m);
  });

  it('strips markdown fences and literal \\n escapes', () => {
    const fenced = '```mermaid\nflowchart TD\\n    A[Start] --> B[End]\n```';
    const fixed = fixMermaidCode(fenced);
    expect(fixed.startsWith('flowchart TD')).toBe(true);
    expect(fixed).toContain('A[Start] --> B[End]');
    expect(fixed).not.toContain('```');
  });

  it('fixes classDiagram colon syntax', () => {
    const broken = `classDiagram
    class User {
        +id: UUID
        +find(id: UUID): User
    }`;
    const fixed = fixMermaidCode(broken);
    expect(fixed).toContain('+id UUID');
    expect(fixed).toContain('+find(id UUID) User');
  });
});
