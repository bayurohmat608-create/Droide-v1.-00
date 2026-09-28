package com.baystudio.droide.core

 
enum class CompletionOrigin { CONTEXT, KEYWORD, SNIPPET, DOCUMENT, LSP }

data class CompletionCandidate(
    val label: String,
    val insertText: String = label,
    val detail: String? = null,
     
    val kind: Int? = null,
    val filterText: String? = null,
    val sortText: String? = null,
    val preselect: Boolean = false,
    val origin: CompletionOrigin,
    val isSnippet: Boolean = false,
)








object ProfessionalCompletion {
    const val LSP_DEBOUNCE_MS = 110L
    const val LSP_TIMEOUT_MS = 2_500L
    const val MAX_LOCAL_ITEMS = 72
    const val MAX_LSP_ITEMS = 120
    const val MAX_VISIBLE_ITEMS = 140
    const val DOCUMENT_SCAN_CHARS = 64 * 1024

    private data class Profile(
        val words: List<String> = emptyList(),
        val extraPrefixChars: Set<Char> = emptySet(),
        val triggerCharacters: Set<String> = DEFAULT_TRIGGER_CHARACTERS,
        val contextual: Map<String, List<String>> = emptyMap(),
    )

    private val DEFAULT_TRIGGER_CHARACTERS = setOf(".", ":", ">", "<", "/", "@", "#")
    private val defaultProfile = Profile()

    



    private val profiles: Map<String, Profile> = mapOf(
        "python" to p("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield match case", triggers = ".@"),
        "javascript" to p("async await break case catch class const continue debugger default delete do else export extends false finally for function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while with yield", triggers = ".?@"),
        "typescript" to p("abstract any as asserts async await bigint boolean break case catch class const constructor continue declare default delete do else enum export extends false finally for from function get if implements import in infer instanceof interface keyof let module namespace never new null number object of override private protected public readonly require return satisfies set static string super switch symbol this throw true try type typeof undefined unique unknown var void while with yield", triggers = ".?:@"),
        "react" to p("async await class const export extends false for function if import interface let null return type true undefined props state children useState useEffect useMemo useCallback", extra = "-", triggers = ".<?:@"),
        "go" to p("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false iota nil", triggers = "."),
        "rust" to p("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while", triggers = ".:"),
        "java" to p("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient true false null try void volatile while var record sealed permits non-sealed", extra = "-", triggers = ".@"),
        "kotlin" to p("as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set where actual abstract annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg", triggers = ".@:"),
        "c" to p("auto break case char const continue default define do double else endif enum extern float for goto if ifdef ifndef include inline int long pragma register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while _Alignas _Alignof _Atomic _Bool _Complex _Generic _Noreturn _Static_assert _Thread_local", triggers = ".>#"),
        "cpp" to p("alignas alignof and and_eq asm auto bitand bitor bool break case catch char char8_t char16_t char32_t class concept const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept not not_eq nullptr operator or or_eq private protected public register reinterpret_cast requires return short signed sizeof static static_assert static_cast struct switch template this thread_local throw true try typedef typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq", triggers = ".:>#"),
        "csharp" to p("abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while async await record var dynamic yield", triggers = ".?@"),
        "php" to p("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile eval exit extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield true false null", triggers = ".>:$"),
        "ruby" to p("BEGIN END alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require attr_reader attr_writer attr_accessor", triggers = ".:"),
        "swift" to p("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as Any catch false is nil super self Self throw throws true try async await actor some any", triggers = ".@"),
        "dart" to p("abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for Function get hide if implements import in interface is late library mixin new null on operator part required rethrow return set show static super switch sync this throw true try typedef var void while with yield", triggers = ".?@"),
        "shell" to p("if then else elif fi for while until do done case esac function in select time coproc true false local readonly export declare typeset return break continue source alias unset printf echo read test trap", extra = "-", triggers = ".$-"),
        "html" to p("html head body title meta link style script main header footer nav section article aside div span p a img picture source form label input textarea select option button ul ol li table thead tbody tr th td canvas svg class id href src alt name value type placeholder role aria-label data-testid", extra = "-:@", triggers = "</=:@", contextual = mapOf("<" to words("html head body main div span p a img section article form input button script style"), "=" to listOf("\"\""))),
        "css" to p("display position color background background-color margin padding width height min-width max-width min-height max-height flex flex-direction flex-wrap grid grid-template-columns grid-template-rows gap align-items align-content justify-content justify-items font font-size font-weight line-height border border-radius overflow opacity transform transition animation cursor content z-index top right bottom left", extra = "-", triggers = ":-(", contextual = mapOf(":" to words("block inline flex grid none auto inherit initial relative absolute fixed sticky center start end transparent"))),
        "json" to p("true false null", triggers = ":\"[{", contextual = mapOf(":" to listOf("\"\"", "true", "false", "null", "[]", "{}"))),
        "yaml" to p("true false null yes no on off", extra = "-.", triggers = ":-[", contextual = mapOf(":" to listOf("true", "false", "null", "[]", "{}", "|", ">"))),
        "markdown" to p("TODO NOTE WARNING IMPORTANT TIP", extra = "-", triggers = "#`[(*_", contextual = mapOf("#" to listOf("# ", "## ", "### "), "[" to listOf("[]()", "[ ] "))),
        "sql" to p("SELECT FROM WHERE JOIN INNER LEFT RIGHT FULL OUTER ON GROUP BY HAVING ORDER INSERT INTO VALUES UPDATE SET DELETE CREATE ALTER DROP TABLE VIEW INDEX DISTINCT UNION ALL AS AND OR NOT NULL IS LIKE IN EXISTS BETWEEN CASE WHEN THEN ELSE END LIMIT OFFSET WITH RETURNING PRIMARY KEY FOREIGN REFERENCES ASC DESC", triggers = ".("),
        "r" to p("if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA library require source return data.frame list matrix factor function", triggers = ".$"),
        "lua" to p("and break do else elseif end false for function goto if in local nil not or repeat return then true until while require pairs ipairs self", triggers = ".:"),
        "perl" to p("my our local state sub use package if elsif else unless while until for foreach continue given when default return undef shift unshift push pop map grep scalar bless", triggers = ".$>"),
        "haskell" to p("case class data default deriving do else foreign if import in infix infixl infixr instance let module newtype of then type where qualified hiding as forall family deriving via", triggers = "."),
        "scala" to p("abstract case catch class def do else extends false final finally for forSome if implicit import lazy match new null object override package private protected return sealed super this throw trait try true type val var while with yield given using enum opaque inline extension", triggers = ".@"),
        "elixir" to p("after alias and case catch cond def defdelegate defexception defguard defimpl defmacro defmodule defp defprotocol defstruct do else end fn for if import in quote raise receive require rescue try unless unquote use when with true false nil", triggers = ".:@"),
        "erlang" to p("after begin case catch cond end fun if let of query receive try when bnot not div rem band and bor bxor bsl bsr or xor orelse andalso module export import record define include", triggers = ".:-"),
        "clojure" to p("def defn defmacro let if if-not when when-not cond case do fn loop recur for doseq map reduce filter require import ns quote var try catch finally throw new set! true false nil", extra = "-?!*+", triggers = ".:"),
        "zig" to p("addrspace align allowzero and anyframe anytype asm async await break catch comptime const continue defer else enum errdefer error export extern false fn for if inline noalias nosuspend null opaque or orelse packed pub resume return linksection struct suspend switch test threadlocal true try union unreachable usingnamespace var volatile while", triggers = ".@"),
        "nim" to p("addr and as asm bind block break case cast concept const continue converter defer discard distinct div do elif else end enum except export finally for from func generic if import in include interface is isnot iterator let macro method mixin mod nil not notin object of or out proc ptr raise ref return shl shr static template try tuple type using var when while with without xor yield", triggers = "."),
        "toml" to p("true false project build-system tool dependencies optional-dependencies scripts requires-python name version description authors license readme classifiers urls", extra = "-", triggers = "[=.", contextual = mapOf("=" to listOf("\"\"", "true", "false", "[]", "{}"), "[" to words("project build-system tool dependencies"))),
        "dockerfile" to p("FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL MAINTAINER AS", extra = "-", triggers = "-$"),
        "makefile" to p("include define endef undefine ifdef ifndef ifeq ifneq else endif override export unexport private vpath .PHONY .DEFAULT .PRECIOUS .INTERMEDIATE .SECONDARY .SECONDEXPANSION .DELETE_ON_ERROR .IGNORE .LOW_RESOLUTION_TIME .SILENT .EXPORT_ALL_VARIABLES .NOTPARALLEL .ONESHELL .POSIX .SUFFIXES", extra = ".$-", triggers = "$(:"),
        "vue" to p("template script setup style scoped ref reactive computed watch onMounted onUnmounted defineProps defineEmits defineExpose v-if v-else v-for v-model v-show v-bind v-on slot component transition keep-alive", extra = "-", triggers = "<.:@", contextual = mapOf("<" to words("template script style div span component slot transition"))),
        "svelte" to p("script style let const import export if each await key html debug on bind class use transition in out animate this", extra = "-", triggers = "<:{@", contextual = mapOf("<" to words("script style div span svelte:component svelte:window"))),
        "graphql" to p("query mutation subscription fragment on schema scalar type interface union enum input directive extend implements repeatable true false null", triggers = ".$(@"),
        "solidity" to p("pragma import contract interface library is using for struct enum event error function modifier constructor fallback receive returns mapping address bool string bytes byte int uint fixed ufixed public private internal external pure view payable constant immutable anonymous indexed memory storage calldata virtual override abstract delete new emit revert require assert this super selfdestruct block msg tx abi", triggers = "."),
        "objectivec" to p("interface implementation protocol property synthesize dynamic selector encode end class public protected private package try catch finally throw synchronized autoreleasepool import include define if else endif typedef struct enum return if for while switch case break continue nil YES NO self super", triggers = ".>@#"),
        "assembly" to p("mov lea push pop call ret jmp cmp test je jne jz jnz ja jb jg jl add sub mul imul div idiv and or xor not shl shr inc dec nop section global extern db dw dd dq byte word dword qword", extra = ".%", triggers = ".%"),
        "powershell" to p("function param begin process end if elseif else switch foreach for while do until try catch finally throw return break continue class enum using namespace module import-module export-modulemember write-host write-output get-item get-childitem set-item new-item remove-item where-object foreach-object select-object", extra = "-", triggers = ".$-"),
        "batch" to p("echo set setlocal endlocal if else for in do goto call exit shift rem pause start pushd popd cd dir copy move del mkdir rmdir type find findstr errorlevel exist defined not enabledelayedexpansion", extra = "%~", triggers = "%:"),
        "vim" to p("let const var set setlocal setglobal function endfunction def enddef if elseif else endif for endfor while endwhile try catch finally endtry autocmd augroup command map nmap imap vmap noremap nnoremap inoremap vnoremap return call execute normal source lua python3", triggers = ".:"),
        "latex" to p("documentclass usepackage begin end section subsection subsubsection paragraph label ref pageref cite textbf textit emph item itemize enumerate figure table includegraphics caption bibliography bibliographystyle newcommand renewcommand frac sqrt sum int alpha beta gamma", triggers = "\\{"),
        "ini" to p("true false yes no on off", extra = "-.", triggers = "[=:;#", contextual = mapOf("=" to words("true false yes no on off"), ":" to words("true false yes no on off"))),
        "properties" to p("true false yes no on off", extra = "-.", triggers = "=:#", contextual = mapOf("=" to words("true false yes no on off"), ":" to words("true false yes no on off"))),
        "prisma" to p("generator datasource model enum type view relation fields references provider url env String Boolean Int BigInt Float Decimal DateTime Json Bytes Unsupported true false null autoincrement cuid uuid now dbgenerated updatedAt id unique default relation map ignore index fulltext", extra = ".", triggers = ".@("),
        "terraform" to p("terraform provider resource data variable output locals module moved import check dynamic for_each count depends_on lifecycle provisioner connection source version required_providers required_version true false null sensitive description type default validation precondition postcondition", extra = "-.", triggers = ".{["),
        "proto" to p("syntax package import option message enum service rpc returns stream repeated optional required reserved oneof map extensions extend group double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes", extra = ".", triggers = ".=("),
    )

    private fun words(value: String): List<String> = value.split(' ').filter(String::isNotBlank)

    private fun p(
        words: String,
        extra: String = "",
        triggers: String = DEFAULT_TRIGGER_CHARACTERS.joinToString(""),
        contextual: Map<String, List<String>> = emptyMap(),
    ) = Profile(
        words = words(words),
        extraPrefixChars = extra.toSet(),
        triggerCharacters = (triggers.map(Char::toString) + DEFAULT_TRIGGER_CHARACTERS).toSet(),
        contextual = contextual,
    )

    fun localProfileLanguageIds(): Set<String> = profiles.keys

    fun hasLocalProfile(languageId: String?): Boolean = languageId?.lowercase() in profiles

    fun prefixFromLine(line: String, column: Int): String = prefixFromLine("", line, column)

    fun prefixFromLine(languageId: String, line: String, column: Int): String {
        val profile = profiles[languageId.lowercase()] ?: defaultProfile
        val end = column.coerceIn(0, line.length)
        var start = end
        while (start > 0 && isPrefixPart(line[start - 1], profile)) start--
        return line.substring(start, end)
    }

    fun triggerCharacter(line: String, column: Int): String? = triggerCharacter("", line, column)

    fun triggerCharacter(languageId: String, line: String, column: Int): String? {
        val profile = profiles[languageId.lowercase()] ?: defaultProfile
        val end = column.coerceIn(0, line.length)
        if (end == 0) return null
        val c = line[end - 1]
        return c.toString().takeIf { !isPrefixPart(c, profile) && !c.isWhitespace() }
    }

    fun shouldQueryLsp(prefix: String, triggerCharacter: String?): Boolean =
        shouldQueryLsp("", prefix, triggerCharacter)

    fun shouldQueryLsp(languageId: String, prefix: String, triggerCharacter: String?): Boolean {
        val profile = profiles[languageId.lowercase()] ?: defaultProfile
        return prefix.isNotEmpty() || triggerCharacter in profile.triggerCharacters
    }

    fun localCandidates(
        languageId: String,
        documentText: String,
        cursorIndex: Int,
        prefix: String,
        triggerCharacter: String? = null,
        codeStyle: CodeStyleProfile? = null,
    ): List<CompletionCandidate> {
        val profile = profiles[languageId.lowercase()] ?: defaultProfile
        val contextualItems = profile.contextual[triggerCharacter].orEmpty().asSequence()
            .map { CompletionCandidate(it, detail = "context", kind = 12, origin = CompletionOrigin.CONTEXT) }

        

        if (prefix.isEmpty() && contextualItems.none()) return emptyList()

        val keywordItems = profile.words.asSequence()
            .filter { it != prefix }
            .map { CompletionCandidate(it, detail = "keyword", kind = 14, origin = CompletionOrigin.KEYWORD) }
        val snippetItems = (ProfessionalSnippets.builtIns(languageId, prefix, codeStyle) +
            DeclarativeSnippetRegistry.candidates(languageId, prefix)).asSequence()

        val start = (cursorIndex - DOCUMENT_SCAN_CHARS / 2).coerceAtLeast(0)
        val end = (start + DOCUMENT_SCAN_CHARS).coerceAtMost(documentText.length)
        val sample = documentText.substring(start, end)
        val documentItems = scanDocumentWords(sample, profile).asSequence()
            .filter { it != prefix && it.length >= 2 }
            .distinct()
            .take(MAX_LOCAL_ITEMS * 2)
            .map { CompletionCandidate(it, detail = "document", kind = 6, origin = CompletionOrigin.DOCUMENT) }

        return rank((contextualItems + snippetItems + keywordItems + documentItems).toList(), prefix, MAX_LOCAL_ITEMS)
    }

    fun rank(candidates: List<CompletionCandidate>, prefix: String, limit: Int = MAX_VISIBLE_ITEMS): List<CompletionCandidate> {
        return candidates.asSequence()
            .mapNotNull { candidate -> score(prefix, candidate)?.let { it to candidate } }
            .sortedWith(
                compareBy<Pair<Int, CompletionCandidate>> { it.first }
                    .thenBy { it.second.sortText ?: "" }
                    .thenBy { it.second.label.length }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.label }
            )
            .map { it.second }
            .distinctBy { it.label to it.insertText }
            .take(limit)
            .toList()
    }

    fun fromLsp(items: List<LspCompletionItem>): List<CompletionCandidate> = items.map {
        CompletionCandidate(
            label = it.label,
            insertText = it.insertText,
            detail = it.detail ?: it.documentation,
            kind = it.kind,
            filterText = it.filterText,
            sortText = it.sortText,
            preselect = it.preselect,
            origin = CompletionOrigin.LSP,
            isSnippet = it.isSnippet,
        )
    }

    fun filterLsp(items: List<LspCompletionItem>, prefix: String): List<LspCompletionItem> {
        val ranked = rank(fromLsp(items), prefix, MAX_LSP_ITEMS)
        val firstByKey = LinkedHashMap<Pair<String, String>, LspCompletionItem>()
        items.forEach { item -> firstByKey.putIfAbsent(item.label to item.insertText, item) }
        return ranked.mapNotNull { candidate -> firstByKey[candidate.label to candidate.insertText] }
            .take(MAX_LSP_ITEMS)
    }

    private fun score(prefix: String, candidate: CompletionCandidate): Int? {
        val target = (candidate.filterText ?: candidate.label).trim()
        if (target.isEmpty()) return null
        val camelIntent = prefix.any(Char::isUpperCase)
        val base = when {
            prefix.isEmpty() -> 40
            target == prefix -> 0
            target.startsWith(prefix) -> 6
            camelIntent && camelMatches(prefix, target) -> 9
            target.startsWith(prefix, ignoreCase = true) -> 12
            camelMatches(prefix, target) -> 20
            subsequenceMatches(prefix, target) -> 28
            target.contains(prefix, ignoreCase = true) -> 36
            else -> return null
        }
        val preselectBonus = if (candidate.preselect) -3 else 0
        val originBonus = when (candidate.origin) {
            CompletionOrigin.LSP -> 0
            CompletionOrigin.CONTEXT -> 0
            CompletionOrigin.SNIPPET -> 1
            CompletionOrigin.KEYWORD -> 2
            CompletionOrigin.DOCUMENT -> 4
        }
        return base + preselectBonus + originBonus
    }

    private fun scanDocumentWords(text: String, profile: Profile): List<String> {
        val out = ArrayList<String>()
        val token = StringBuilder()
        fun flush() {
            if (token.length >= 2) out += token.toString()
            token.setLength(0)
        }
        text.forEach { c ->
            if (isPrefixPart(c, profile)) token.append(c) else flush()
        }
        flush()
        return out
    }

    private fun camelMatches(query: String, value: String): Boolean {
        if (query.isEmpty()) return true
        val initials = buildString {
            value.forEachIndexed { index, c ->
                if (index == 0 || c.isUpperCase() || value.getOrNull(index - 1)?.let { !it.isLetterOrDigit() } == true) append(c)
            }
        }
        return initials.startsWith(query, ignoreCase = true)
    }

    private fun subsequenceMatches(query: String, value: String): Boolean {
        if (query.isEmpty()) return true
        var q = 0
        for (c in value) {
            if (c.equals(query[q], ignoreCase = true)) {
                q++
                if (q == query.length) return true
            }
        }
        return false
    }

    private fun isPrefixPart(c: Char, profile: Profile): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' || c in profile.extraPrefixChars
}
