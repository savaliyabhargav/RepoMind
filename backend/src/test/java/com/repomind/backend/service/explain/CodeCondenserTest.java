package com.repomind.backend.service.explain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeCondenserTest {

    @Test
    void condenseStripsCommentsImportsAndBlankLines() {
        String code = """
                package com.example;

                import java.util.List;
                import static java.util.Map.of;

                /* block
                   comment */
                public class Foo {
                    // line comment
                    private int x;

                    public int getX() { return x; }
                }
                """;
        String out = CodeCondenser.condense(code);
        assertThat(out).doesNotContain("import java.util.List");
        assertThat(out).doesNotContain("package com.example");
        assertThat(out).doesNotContain("block");
        assertThat(out).doesNotContain("line comment");
        assertThat(out).contains("private int x;");
        assertThat(out).contains("public int getX() { return x; }");
        assertThat(out).doesNotContain("\n\n");
    }

    @Test
    void condenseStripsJsAndPythonImports() {
        String code = """
                import { api } from "./authService";
                from collections import defaultdict
                # a python comment
                const x = 1;
                """;
        String out = CodeCondenser.condense(code);
        assertThat(out).doesNotContain("authService");
        assertThat(out).doesNotContain("defaultdict");
        assertThat(out).doesNotContain("python comment");
        assertThat(out).contains("const x = 1;");
    }

    @Test
    void skeletonKeepsSignaturesAndControlFlowElidesDeepBodies() {
        String code = """
                public class Foo {
                    public void run(int n) {
                        if (n > 0) {
                            int a = 1;
                            int b = 2;
                            log(a + b);
                        }
                    }
                }
                """;
        String out = CodeCondenser.skeleton(code);
        assertThat(out).contains("public void run(int n) {");
        assertThat(out).contains("if (n > 0) {");
        // depth-3 statements are elided into a single marker
        assertThat(out).doesNotContain("int a = 1;");
        assertThat(out).contains("...");
    }

    @Test
    void handlesNullAndBlankInput() {
        assertThat(CodeCondenser.condense(null)).isEmpty();
        assertThat(CodeCondenser.condense("   ")).isEmpty();
        assertThat(CodeCondenser.skeleton(null)).isEmpty();
    }
}
