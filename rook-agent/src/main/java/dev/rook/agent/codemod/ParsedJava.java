package dev.rook.agent.codemod;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import java.util.Optional;

final class ParsedJava {

    private final CompilationUnit unit;
    private final String content;
    private final boolean lexical;

    private ParsedJava(CompilationUnit unit, String content, boolean lexical) {
        this.unit = unit;
        this.content = content;
        this.lexical = lexical;
    }

    static Optional<ParsedJava> parse(String content) {
        return parse(content, true);
    }

    static Optional<ParsedJava> parse(String content, boolean lexical) {
        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        configuration.setStoreTokens(true);
        ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return Optional.empty();
        }
        CompilationUnit unit = result.getResult().get();
        if (lexical) {
            LexicalPreservingPrinter.setup(unit);
        }
        return Optional.of(new ParsedJava(unit, content, lexical));
    }

    CompilationUnit unit() {
        return unit;
    }

    String content() {
        return content;
    }

    String print() {
        if (!lexical) {
            throw new IllegalStateException("this source was parsed without a printer");
        }
        return LexicalPreservingPrinter.print(unit);
    }
}
