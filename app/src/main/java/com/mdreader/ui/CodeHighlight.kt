package com.mdreader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * A small, dependency-free syntax highlighter for the code blocks that turn up in
 * books: prose examples, JSON, shell snippets, Kotlin/Java/JS/Python, SQL, markup,
 * and configuration files. It is intentionally conservative: anything unknown is
 * rendered as plain monospace text rather than being mangled.
 */
@Composable
fun highlightCode(code: String, language: String?): AnnotatedString {
    val lang = normalizeLanguage(language)
    if (lang == null || lang !in SUPPORTED_LANGUAGES) return AnnotatedString(code)

    val colors = MaterialTheme.colorScheme
    val keywordColor = colors.primary
    val stringColor = colors.tertiary
    val commentColor = colors.onSurfaceVariant.copy(alpha = 0.68f)
    val numberColor = colors.secondary
    val annotationColor = colors.secondary
    val functionColor = colors.onSurface

    if (lang == "diff" || lang == "patch") {
        return buildAnnotatedString {
            code.lineSequence().forEach { line ->
                val color = when {
                    line.startsWith("+") -> colors.tertiary
                    line.startsWith("-") -> colors.error
                    line.startsWith("@@") -> colors.primary
                    else -> colors.onSurface
                }
                withStyle(SpanStyle(color = color)) {
                    append(line)
                    append('\n')
                }
            }
        }
    }

    val keywords = keywordsFor(lang)
    val hashComments = lang in HASH_COMMENT_LANGUAGES
    val slashComments = lang in SLASH_COMMENT_LANGUAGES
    val dashComments = lang in DASH_COMMENT_LANGUAGES
    val htmlComments = lang in HTML_LANGUAGES
    val functionNames = lang in FUNCTION_LANGUAGES

    return buildAnnotatedString {
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                append(plain.toString())
                plain.clear()
            }
        }

        fun appendStyled(text: String, style: SpanStyle) {
            flush()
            withStyle(style) { append(text) }
        }

        var index = 0
        while (index < code.length) {
            val char = code[index]
            val rest = code.substring(index)

            if (slashComments && rest.startsWith("/*")) {
                val end = code.indexOf("*/", index + 2)
                val stop = if (end >= 0) end + 2 else code.length
                appendStyled(code.substring(index, stop), SpanStyle(color = commentColor))
                index = stop
                continue
            }

            if (htmlComments && rest.startsWith("<!--")) {
                val end = code.indexOf("-->", index + 4)
                val stop = if (end >= 0) end + 3 else code.length
                appendStyled(code.substring(index, stop), SpanStyle(color = commentColor))
                index = stop
                continue
            }

            if (slashComments && rest.startsWith("//")) {
                val end = code.indexOf('\n', index).let { if (it < 0) code.length else it }
                appendStyled(code.substring(index, end), SpanStyle(color = commentColor))
                index = end
                continue
            }

            if (dashComments && rest.startsWith("--")) {
                val end = code.indexOf('\n', index).let { if (it < 0) code.length else it }
                appendStyled(code.substring(index, end), SpanStyle(color = commentColor))
                index = end
                continue
            }

            if (hashComments && char == '#') {
                val end = code.indexOf('\n', index).let { if (it < 0) code.length else it }
                appendStyled(code.substring(index, end), SpanStyle(color = commentColor))
                index = end
                continue
            }

            if (char == '"' || char == '\'' || char == '`') {
                val end = stringEnd(code, index, char)
                appendStyled(code.substring(index, end), SpanStyle(color = stringColor))
                index = end
                continue
            }

            if (char.isDigit()) {
                var end = index + 1
                while (end < code.length && (
                        code[end].isLetterOrDigit() ||
                            code[end] == '.' ||
                            code[end] == '_'
                        )
                ) {
                    end++
                }
                appendStyled(code.substring(index, end), SpanStyle(color = numberColor))
                index = end
                continue
            }

            if (char.isLetter() || char == '_' || char == '@') {
                val start = index
                if (char == '@') index++
                while (index < code.length && (
                        code[index].isLetterOrDigit() ||
                            code[index] == '_'
                        )
                ) {
                    index++
                }
                val token = code.substring(start, index)
                val style = when {
                    token.startsWith("@") -> SpanStyle(color = annotationColor)
                    token.lowercase() in keywords -> SpanStyle(
                        color = keywordColor,
                        fontWeight = FontWeight.Medium,
                    )

                    functionNames && nextNonSpace(code, index) == '(' ->
                        SpanStyle(color = functionColor)

                    else -> null
                }
                if (style != null) {
                    appendStyled(token, style)
                } else {
                    plain.append(token)
                }
                continue
            }

            plain.append(char)
            index++
        }

        flush()
    }
}

private fun normalizeLanguage(language: String?): String? {
    val raw = language
        ?.trim()
        ?.substringBefore(' ')
        ?.trim()
        ?.removePrefix("{.")
        ?.removePrefix("{")
        ?.removeSuffix("}")
        ?.removePrefix(".")
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return when (raw) {
        "kt", "kts" -> "kotlin"
        "js", "mjs", "cjs" -> "javascript"
        "ts" -> "typescript"
        "py" -> "python"
        "rb" -> "ruby"
        "rs" -> "rust"
        "sh", "bash", "zsh", "shell", "console" -> "shell"
        "yml" -> "yaml"
        "c++", "cxx", "cc", "hpp", "h++" -> "cpp"
        "c#" -> "csharp"
        "objc" -> "objective-c"
        "htm" -> "html"
        "xml", "svg", "xhtml" -> "xml"
        "md" -> "markdown"
        "tex", "latex" -> "latex"
        "ps1", "powershell" -> "powershell"
        "docker", "dockerfile" -> "dockerfile"
        "make", "makefile" -> "makefile"
        else -> raw
    }
}

private fun stringEnd(code: String, start: Int, quote: Char): Int {
    val triple = start + 2 < code.length &&
        code[start + 1] == quote &&
        code[start + 2] == quote

    var index = start + if (triple) 3 else 1
    while (index < code.length) {
        if (code[index] == '\\') {
            index += 2
            continue
        }
        if (triple) {
            if (index + 2 < code.length &&
                code[index] == quote &&
                code[index + 1] == quote &&
                code[index + 2] == quote
            ) {
                return index + 3
            }
        } else if (code[index] == quote) {
            return index + 1
        } else if (code[index] == '\n') {
            return index
        }
        index++
    }
    return code.length
}

private fun nextNonSpace(code: String, start: Int): Char? {
    var index = start
    while (index < code.length && code[index].isWhitespace()) index++
    return code.getOrNull(index)
}

private fun keywordsFor(language: String): Set<String> = when (language) {
    "kotlin" -> COMMON_KEYWORDS + KOTLIN_KEYWORDS
    "java", "scala", "groovy", "gradle" -> COMMON_KEYWORDS + JVM_KEYWORDS
    "javascript", "typescript", "jsx", "tsx", "json" -> COMMON_KEYWORDS + JS_KEYWORDS
    "python" -> COMMON_KEYWORDS + PYTHON_KEYWORDS
    "c", "cpp", "csharp", "objective-c", "swift", "dart" -> COMMON_KEYWORDS + C_KEYWORDS
    "rust" -> COMMON_KEYWORDS + RUST_KEYWORDS
    "go" -> COMMON_KEYWORDS + GO_KEYWORDS
    "ruby" -> COMMON_KEYWORDS + RUBY_KEYWORDS
    "php" -> COMMON_KEYWORDS + PHP_KEYWORDS
    "shell", "powershell", "makefile", "dockerfile" -> COMMON_KEYWORDS + SHELL_KEYWORDS
    "sql" -> COMMON_KEYWORDS + SQL_KEYWORDS
    "r" -> COMMON_KEYWORDS + R_KEYWORDS
    "lua" -> COMMON_KEYWORDS + LUA_KEYWORDS
    "yaml", "toml", "ini", "properties" -> COMMON_KEYWORDS + CONFIG_KEYWORDS
    else -> COMMON_KEYWORDS
}

private val SUPPORTED_LANGUAGES = setOf(
    "kotlin", "java", "scala", "groovy", "gradle", "javascript", "typescript", "jsx", "tsx",
    "python", "ruby", "rust", "go", "c", "cpp", "csharp", "objective-c", "swift", "dart",
    "php", "shell", "powershell", "sql", "r", "lua", "yaml", "toml", "ini", "properties",
    "json", "xml", "html", "css", "scss", "sass", "less", "markdown", "diff", "patch",
    "dockerfile", "makefile", "http", "graphql", "protobuf", "tex", "latex", "mermaid",
)

private val HASH_COMMENT_LANGUAGES = setOf(
    "python", "ruby", "shell", "powershell", "yaml", "toml", "ini", "properties",
    "dockerfile", "makefile", "r", "perl", "graphql",
)

private val SLASH_COMMENT_LANGUAGES = setOf(
    "kotlin", "java", "scala", "groovy", "gradle", "javascript", "typescript", "jsx", "tsx",
    "c", "cpp", "csharp", "objective-c", "swift", "dart", "php", "rust", "go",
    "css", "scss", "sass", "less", "json", "protobuf",
)

private val DASH_COMMENT_LANGUAGES = setOf("sql", "lua")
private val HTML_LANGUAGES = setOf("xml", "html", "svg", "xhtml")
private val FUNCTION_LANGUAGES = setOf(
    "kotlin", "java", "scala", "groovy", "javascript", "typescript", "jsx", "tsx",
    "python", "ruby", "rust", "go", "c", "cpp", "csharp", "swift", "dart", "php", "r", "lua",
)

private val COMMON_KEYWORDS = setOf(
    "if", "else", "for", "while", "do", "return", "break", "continue", "switch", "case",
    "default", "try", "catch", "finally", "throw", "throws", "true", "false", "null",
    "none", "nil", "undefined", "new", "this", "self", "super", "class", "interface",
    "struct", "enum", "type", "func", "function", "fun", "def", "fn", "sub", "let", "var",
    "val", "const", "static", "final", "public", "private", "protected", "internal",
    "package", "import", "from", "export", "as", "in", "is", "of", "with", "where",
    "when", "match", "loop", "async", "await", "yield", "suspend", "override", "open",
    "abstract", "sealed", "data", "object", "companion", "inline", "operator", "infix",
    "lateinit", "by", "out", "ref", "readonly", "mut", "pub", "use", "mod", "crate",
    "impl", "trait", "dyn", "move", "unsafe", "extern", "sizeof", "typedef", "namespace",
    "using", "template", "typename", "auto", "virtual", "explicit", "friend", "operator",
    "delete", "goto", "register", "volatile", "sizeof", "assert", "raise", "except",
    "lambda", "pass", "global", "nonlocal", "del", "not", "and", "or", "xor", "begin",
    "end", "then", "elsif", "unless", "until", "module", "require", "include", "extend",
    "defer", "chan", "select", "range", "map", "make", "append", "len", "cap", "copy",
    "print", "println", "fmt", "panic", "recover", "go", "package", "nil", "iota",
    "typeof", "instanceof", "debugger", "keyof", "declare", "implements", "extends",
    "get", "set", "constructor", "init", "record", "with", "params", "goto", "foreach",
)

private val KOTLIN_KEYWORDS = setOf(
    "actual", "annotation", "companion", "const", "crossinline", "expect", "external",
    "field", "file", "get", "import", "init", "inner", "internal", "lateinit", "noinline",
    "open", "operator", "out", "override", "package", "param", "private", "property",
    "protected", "public", "receiver", "reified", "sealed", "set", "setparam", "super",
    "suspend", "tailrec", "this", "typealias", "value", "vararg", "when", "where",
)

private val JVM_KEYWORDS = setOf(
    "boolean", "byte", "char", "double", "float", "int", "long", "short", "void",
    "synchronized", "transient", "native", "strictfp", "instanceof", "extends",
    "implements", "interface", "enum", "record", "yield", "var", "sealed", "permits",
)

private val JS_KEYWORDS = setOf(
    "let", "const", "var", "function", "return", "if", "else", "for", "while", "do",
    "switch", "case", "break", "continue", "new", "this", "super", "class", "extends",
    "import", "export", "from", "default", "async", "await", "yield", "try", "catch",
    "finally", "throw", "typeof", "instanceof", "delete", "void", "in", "of", "null",
    "undefined", "true", "false", "interface", "type", "enum", "namespace", "declare",
    "readonly", "keyof", "as", "satisfies", "infer", "implements", "private", "public",
    "protected", "static", "get", "set", "debugger", "with",
)

private val PYTHON_KEYWORDS = setOf(
    "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
    "pass", "import", "from", "as", "try", "except", "finally", "raise", "with", "lambda",
    "yield", "async", "await", "global", "nonlocal", "assert", "del", "in", "is", "not",
    "and", "or", "None", "True", "False", "self",
)

private val C_KEYWORDS = setOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
    "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
    "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
    "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "class",
    "namespace", "template", "typename", "public", "private", "protected", "virtual",
    "override", "final", "new", "delete", "this", "using", "try", "catch", "throw",
    "operator", "friend", "explicit", "nullptr", "true", "false", "constexpr", "decltype",
)

private val RUST_KEYWORDS = setOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
    "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
    "trait", "true", "type", "unsafe", "use", "where", "while", "union",
)

private val GO_KEYWORDS = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
    "return", "select", "struct", "switch", "type", "var", "nil", "true", "false", "iota",
)

private val RUBY_KEYWORDS = setOf(
    "BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?",
    "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next",
    "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super", "then",
    "true", "undef", "unless", "until", "when", "while", "yield", "require", "include",
)

private val PHP_KEYWORDS = setOf(
    "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
    "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
    "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile",
    "eval", "exit", "extends", "final", "finally", "fn", "for", "foreach", "function",
    "global", "goto", "if", "implements", "include", "include_once", "instanceof",
    "insteadof", "interface", "isset", "list", "match", "namespace", "new", "or",
    "print", "private", "protected", "public", "readonly", "require", "require_once",
    "return", "static", "switch", "throw", "trait", "try", "unset", "use", "var",
    "while", "xor", "yield", "true", "false", "null",
)

private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
    "esac", "function", "in", "select", "time", "coproc", "return", "exit", "break",
    "continue", "local", "export", "readonly", "declare", "typeset", "unset", "shift",
    "source", "alias", "unalias", "set", "echo", "printf", "read", "cd", "pwd", "test",
)

private val SQL_KEYWORDS = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
    "create", "table", "alter", "drop", "index", "view", "join", "inner", "left", "right",
    "full", "outer", "on", "group", "by", "order", "having", "limit", "offset", "distinct",
    "as", "and", "or", "not", "null", "is", "in", "between", "like", "exists", "union",
    "all", "case", "when", "then", "else", "end", "primary", "key", "foreign", "references",
    "constraint", "default", "unique", "check", "begin", "commit", "rollback", "transaction",
    "with", "recursive", "returning", "asc", "desc", "true", "false",
)

private val R_KEYWORDS = setOf(
    "if", "else", "repeat", "while", "function", "for", "in", "next", "break", "TRUE",
    "FALSE", "NULL", "Inf", "NaN", "NA", "library", "require", "return", "switch",
)

private val LUA_KEYWORDS = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
    "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until",
    "while",
)

private val CONFIG_KEYWORDS = setOf("true", "false", "null", "yes", "no", "on", "off")
