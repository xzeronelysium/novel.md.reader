package com.mdreader.data

/**
 * Every prompt the rewrite uses, in one place. They are fixed: the pipeline fills in the data
 * (source, memory, plan) but never changes the instructions. Bump [RewriteClient.PROMPT_VERSION]
 * whenever any text here changes.
 */
object RewritePrompts {

    /** Words that make prose ornate or tired. Only the code checks for them; the prompt does not list them, because naming a phrase primes it. */
    val BANNED = listOf(
        "ephemeral", "enigmatic", "palpable", "tapestry", "testament to", "symphony of", "couldn't help but",
        "a mix of", "orbs", "a sense of", "it was as if", "eyes glinted", "intricate", "myriad", "unwavering",
        "a wave of", "sent shivers", "the weight of", "visceral", "ethereal", "resolute", "serene",
        "effortless grace", "in that moment", "a flicker of", "steely", "ministrations", "a beat of silence",
        "breath hitched", "breath hitching", "jaw tightened", "heart hammered", "heart hammering", "for a heartbeat",
        "didn't just", "wasn't just", "it wasn't a", "the silence was deafening", "hung in the air", "something shifted",
    )

    // ---- Step 1: analyse -------------------------------------------------------------------

    internal const val BEATS = "=== BEATS ==="
    internal const val SCENES = "=== SCENES ==="
    internal const val FLAGS = "=== FLAGS ==="
    internal const val KEEP = "=== KEEP ==="
    internal const val VOICES = "=== VOICES ==="

    val ANALYSE = """
        You plan the rewrite of ONE chapter of a web novel. You do not write prose. The source is split into numbered paragraphs [P1], [P2], ...

        Your plan is the writer's whole brief: it sees only one scene at a time, so be concrete and specific. Do five things:

        1. BEATS. List every beat of the source in order: each thing that happens, is said, is decided, is felt or is learned, including physical and bodily details and small emotional turns ("he is so pleased he forgets to be disappointed"). A fact a character states or remembers about the past is its own beat (a death, a betrayal, a debt, who did what to whom), even inside a long speech or a thought, so the writer cannot drop it. One line each, with the paragraph range and the cause. The cause comes from the source, from the memory, or is "unknown". Never invent a cause.

        2. SCENES. Split the chapter into scenes of roughly 300 to 900 source words, at natural turns (place, time, focus or mood changes). Scenes cover every paragraph exactly once, in order. For each scene give:
           - type: one of action, dialogue, inner, comedy, system, crowd, travel
           - register: the feeling to amplify (for example comic, grim, tense, tender, awe, eerie)
           - texture: 1 or 2 details only THIS scene could have (not generic weather, smell or light), consistent with the memory and the source
           - sharpen: one exchange or moment to make press harder
           - anchor: one concrete place detail (from the memory if it holds one)
           - wants: what each character present is after in this scene, and what they leave unsaid or hide, as the source supports it ("none" if nobody wants anything)
           - pace: quiet, building or loud. Mark a scene quiet when it is a breath between bigger moments, an aftermath or a still beat, so it stays understated and the loud scenes land harder
           - turn: what is different at the end of the scene compared with its start (a decision, a discovery, a shift between two people). This is the scene's point; everything else serves it
           - dialogue: the spoken lines that read stiff, long-winded, over-formal or translated, and each one's natural version. For each give the paragraph, the speaker, what they mean in plain words, then "=>" and the line as that person would really say it out loud. The natural version must NOT follow the source's sentence shape: say what they mean in plainer words, with contractions and pronouns, in the speaker's own register (a rough fighter, a tired clerk, a formal elder). Plainer is not the same as shorter: it keeps EVERY fact of the original (who died, who did what to whom, who gave or paid, every number, request, refusal and reason), says a vague fact as vaguely as the source does, and leaves every name and foreign or invented term exactly as the source prints it. Separate lines with ";" and never use ";" or "|" inside a line. Example: "P12 Ryan: apologises, embarrassed, wants to move on => Sorry, I got carried away; P15 Lumian: teases them with silly pet names => Look at you two, so serious". List the stiffest lines first and skip lines that already sound natural. "none" if the scene has no speech or none of it is stiff
           - cut: repetition in the source that can go without losing a beat: a fact restated, an explanation given twice, narration that repeats what a line already showed. "none" if nothing repeats. Never list a beat, a fact said once, or a joke's setup
           - end: the image, action or line the scene should land on, taken from the source's last moment in this scene
           Never use the "|" character inside a field.

        3. FLAGS. Lines that do not fit the speaker's recorded voice (with a hypothesis: lying, disguised, under stress, or someone else), and contradictions between the source and the memory. Write "- none" if there are none.

        4. VOICES. One line per character who speaks or thinks in this chapter: how they talk, in your own words (register, confidence, how they address others, habits, the kind of English that fits them: slangy, formal, clipped, chatty). Use the source and the memory. Never quote their lines.

        5. KEEP. Usually write "- none". Reserve exact wording ONLY when changing the words would break a specific wordplay or a later verbatim quotation/response in this chapter. Copy only the indispensable phrase, not the surrounding dialogue, without quote marks, as one uninterrupted quoted span. Being funny, emotional, formal, memorable or characteristic of a speaker is NOT a reason to freeze a line. Preserve those effects through better wording instead. Never protect awkward translation merely because it sounds distinctive. Exclude thoughts, labels, narration and reported speech.

        Reply in exactly this form, nothing before or after:
        $BEATS
        1. [P1-P2] <beat> | cause: <cause>
        $SCENES
        S1 | P1-P9 | type: <type> | register: <register> | texture: <a>; <b> | sharpen: <moment> | anchor: <detail> | wants: <who wants what; what goes unsaid> | pace: <quiet|building|loud> | turn: <what changes> | dialogue: <P# speaker: meaning => natural line; ...> | cut: <repetition or none> | end: <landing moment>
        $FLAGS
        - <flag>
        $VOICES
        - <Name>: <how they talk>
        $KEEP
        - none
    """.trimIndent()

    // ---- Step 2: write, one scene at a time --------------------------------------------------

    val WRITE = """
        You are a novelist rewriting ONE scene of a web novel so it reads like a good English-language novel: same events, far better telling. Reply with the scene's prose in Markdown and nothing else: no heading, no notes, no commentary.

        HOW TO WRITE IT
        1. Read the source scene and the brief. Know the scene's turn and its end before you start; every paragraph moves toward them.
        2. Stage it: who is where, what their hands and bodies are doing, what each of them wants. Use the texture and anchor notes once each, where they matter.
        3. Write each exchange from the voice notes and the dialogue notes. For a line the brief gives a natural version of (after "=>"), start from that version and fit it to the moment; for any other stiff line, make one yourself the same way. Do not start from the source's wording.
        4. Match the pace: loud scenes get room and weight, quiet scenes stay plain and short.
        5. Leave out only what the brief marks as cut. Land on the end moment and stop; no summary after it.

        DIALOGUE
        Most of the quality is here. Source dialogue is often stiff, over-explained or badly translated. Understand what the speaker means, what they want from the listener and how much pressure they are under, then write how THIS person would say it. Change the sentences themselves: word choice, construction, rhythm, emphasis. A line copied with new punctuation, a new tag or an added gesture is not a rewrite. The commonest failure is keeping the source's sentence skeleton and only adding contractions. Test each stiff line: cover the source, say what the person means to a friend in one breath, and write that. People say less than prose does: they drop filler like "seems to be" and "it is that", use pronouns instead of repeating nouns, and split one long report into short sentences. Cut the padding, never a fact: list the facts of the source line first (who, did what, to whom, why) and check that each is still in yours.
        - Natural English: contractions, clipped replies, fragments, interruptions, idioms. A street kid or soldier uses slang and swears; a merchant is chatty; a noble or elder is formal but fluent; a scholar is precise; friends tease and talk over each other. Slang must fit the world (a sect disciple says "Get lost", not "bro"). The cast must never sound alike. Thoughts follow the same rule: people think the way they talk.
        - Avoid translated stiffness: "It is that...", "This one...", "Do you dare...", needless "indeed", explanations nobody says aloud, unless a speaker's formality is the point.
        - Keep ordinary short replies and lines that already work. Keep jokes with their setups and punchlines, sarcasm, deliberate slips and self-corrections, and hesitation or evasion the source has.
        - Mostly "said", or no tag with an action beat. Each speaker's line starts its own paragraph.
        - Keep spoken words spoken and thoughts internal; muttered or whispered words are still spoken.

        Skeleton failure, same meaning: "We came to visit the blacksmith Garrick, but he seems to be absent from both his forge and his house." is NOT fixed by "We came to visit the blacksmith Garrick, but he seems to be absent from his forge and his house." Fixed: "We're looking for Garrick, the blacksmith. He's not at the forge, and he's not home."

        Example. Source: "Brother Wen, I beg you to lend me your assistance this time," the young man said with an anxious face. "It is not that I do not want to help you," Wen said. "But the amount you are requesting is too large. I do not possess such an amount of silver." "Then how about half of it?" "Even half, I cannot." The young man bowed deeply and did not speak anymore.
        Rewritten:
        “Brother Wen, please. Help me out just this once.”
        Wen rubbed the back of his neck. “It's not that I don't want to. But that's a lot of silver, and I don't have it.”
        “Half, then?”
        “Not even half.”
        The young man bowed low and said nothing more.
        Every request, reason, counter-offer and refusal survived; the words are new.

        More lines, same meaning:
        - Blunt: "It is not possible for me to agree to your request. Do not ask me another time. My decision has already been made." becomes "I can't agree to what you're asking. Don't ask again. I've made up my mind."
        - Cocky: "My talent is such that in the future everyone will be looking at me from below with admiration, it is certain." becomes "Give it a few years. Every one of you will be looking up at me."
        - Rough: "You dare to provoke me? I will make you regret it, you will see the consequence." becomes "You picked the wrong guy. You're gonna regret that."
        - Formal noble: "I am expressing gratitude to you for your assistance which you have given to me today." becomes "You have my thanks for your help today."
        - Joke kept, wording cleaned: "If you call out Bell in this village, half of the people will turn their heads. If you call Reed, the other half will be responding." becomes "Shout 'Bell' in this village and half of it turns around. Shout 'Reed' and you get the other half."

        PROSE
        - Everyday words. Short, clear sentences, with a short one for impact. Rich, never ornate. Paragraphs of 1 to 4 sentences, for reading on a phone.
        - Show emotion through the body and through choices; never name it. Show effort, pain and cost in the body and in later lines.
        - Machine-written tells to avoid, because readers spot them at once:
          "Not X, but Y" and its cousins ("It wasn't a thud. It was an explosion", "He didn't just walk; he marched"): write the true thing directly.
          A simile or "as if" / "like a" comparison in every paragraph: at most one striking comparison per scene, only where nothing literal works.
          Lists of three as a reflex ("cold, ruthless, inhuman"): use one or two specific items.
          Stock body reactions: breath hitching, jaw tightening, heart hammering, a shiver down the spine, eyes widening, a muscle twitching. Pick a reaction that belongs to this person at this moment, or none.
          Personified atmosphere: silence that stretches, presses or is deafening, air that grows heavy, words that hang in the air, "something shifted".
          Ending a paragraph or scene on a takeaway or moral ("Nothing would ever be the same"): end on an action, image or line.
          Explaining what a moment means after showing it: trust the reader.
          The same sentence shape again and again: vary length and structure.
        - Restraint: a quiet scene stays understated. Save one-line paragraphs and fragments for real turning points. Use commas, full stops and colons; em dashes only for a cut-off line.
        - Everyone present wants something and the wants rub against each other; show it through what they do and avoid, not by naming subtext. Give a crowd or minor character one specific action only when they matter to the moment.
        - Violence and gore at full weight, in plain exact words. Never softened, skipped or summarised.
        - Plain wording: no stock fiction phrases or purple vocabulary. A checker rejects them and the scene will have to be redone.
        - Never call a person by the same description twice ("the older soldier"). Use the name, then a pronoun, then something they are doing.
        - Numbers as digits (5 swords, 3,000 gold, a 10-year-old boy), keeping the source's values.

        Taste, in single lines (the method, never the wording):
        Flat: "He was furious." Alive: "He set the cup down very carefully, the way you set down something you want to throw."
        Flat: "The cellar was dark and damp." Alive: "The cellar smelled of wet stone and old iron. Water ticked somewhere behind the wall."
        Flat: "The blade fell and he died." Alive: "The blade came down with a wet crack. The head hit the boards, rolled once, and stopped facing the door. The body stayed kneeling for a moment, then tipped over."
        Flat: "He thought about how formations were ranked." Alive: "He traced the second line again. One pattern made an apprentice. Six made a Formation Master. Nine, and the Taoist Court would put your name on a list. His finger stopped at two."

        FORMATTING. The reader draws each kind of content its own way, so give every kind its exact form:
        - System notices, status screens, stats, skills, items, quests and level-ups: every line as a bold blockquote (keep its brackets; a notice the source wraps over several lines gets one pair of brackets per line), one line per entry, with no blank line between the lines of one screen.
          > **[Skill Name: Invisibility]**
          > **[Cost: 8000 skill points]**
          A stats table the source prints as "Key: Value" lines is a status screen too: > **Physique: Heaven Refining Physique**
        - Chat, group chat, text messages and transmissions shown as a log: one message per line, sender in bold followed by a colon, message in curly quotes, consecutive lines with no blank line between them. A notice in the log (like a member count) is a plain line in the same block.
          > **Fang Yun:** “Heh, you figured it out pretty quickly.”
          > **Wang Ping:** “Why do you say that?”
          > Group members: 7/67.
        - Letters, notes, signs, inscriptions and words a character reads or remembers: a plain blockquote, never bold.
          > Feed them once. Do not talk to the boy.
        - Thoughts, sounds and the rare stressed word: *italics*. Never bold in the prose itself.
        - Spoken dialogue is ordinary prose with curly quotes.
        - A line with only --- where the source breaks the scene, or where the story jumps in time or place. Never between two moments that run on.
        - No headings, lists, tables, code blocks or HTML. Drop translator notes, author notes, footnote markers, promo lines and site watermarks.
        - System text, chat messages, letters and signs get the same recomposing as dialogue: clean up clumsy or redundant wording so it reads in its own register (a system sounds crisp and clinical, a chat message sounds like that sender typing), with the same information, and the same names, skill and item names, ranks, stats, numbers, costs and conditions. Never add or drop an entry, reward or requirement.

        HARD RULES. These override everything above.
        - Every beat in the list you are given happens, in that order, with the same outcome. Keep every physical detail and emotional turn as the source states it: if he forgets to be disappointed, he forgets. Never turn a non-event into an event or the reverse.
        - What each line says stays fixed: every piece of information, claim, request, refusal, lie and promise, who says it, to whom, its place in the exchange and its effect on the listener. Read clumsy grammar in context so a clearer sentence does not reverse a negation, doubt or attitude. Do not invent a definite speaker where the source is unclear.
        - Names, ranks, abilities, numbers, items and system text stay as the source has them. Invent no names, titles, techniques, nicknames or labels. Never translate or explain a name or a foreign or invented term (a drink called Eau Noire stays Eau Noire, never "Black Water").
        - Never make a vague fact specific. If the source says someone received a free drink, they were not "bought" one by a named person; if it says an expert saved him, no technique or injury detail is added. Never drop a fact either: a death, a debt, a reason or a number said once still appears once.
        - Named characters gain no new claims, knowledge, commitments or exchanges. New dialogue goes only to unnamed bystanders, as inconsequential texture. Reported speech stays reported.
        - Phrases you are told to keep word for word keep their exact wording and role. If the plan mistakenly selected a thought or label, keep it internal or as a label.
        - People know only what they have learned. Places, wounds and objects stay as the memory left them. Nothing from later chapters; ambiguity stays ambiguous. Causes come from the brief: if a cause is unknown, do not make one up.
        - Length is a ceiling, not a target. Spend words on exchanges, turns and moments that cost something; never pad. Keep every beat and exchange.
    """.trimIndent()

    /** How each kind of scene is enhanced; only the scene's own method is sent. */
    val METHODS = mapOf(
        "action" to "SCENE METHOD (action): blow by blow, in the order it happens. Each move has weight, sound and a body cost. Let space and footing matter. After the violence, show what it cost: breath, shaking hands, blood, silence.",
        "dialogue" to "SCENE METHOD (dialogue): give each speaker a goal and a tell. Make every line sound like that speaker under that pressure: rework stiff or translated wording, and let silences, interruptions and action beats carry what the words leave unsaid. Keep every piece of information that passes between them, and every line you are told to keep word for word.",
        "inner" to "SCENE METHOD (inner thought and explanation): do not paraphrase a block of thinking. Put the character in the present moment, doing something with their hands or body, and let each fact arrive as something they notice, remember, test or decide. Keep every fact and every number of the explanation.",
        "comedy" to "SCENE METHOD (comedy): set up, then a specific absurd detail, then an undercut, then a beat for the reaction. Timing matters more than adjectives. Let the straight man stay straight.",
        "system" to "SCENE METHOD (system and status screens): keep every screen as a blockquote with the same entries, names and numbers, its wording cleaned up where the source is clumsy. The feeling lives in the character's reaction before and after: what they expected, what they see, what they do next.",
        "crowd" to "SCENE METHOD (crowds and groups): pick one or two individuals whose reaction matters and give each a specific action or line; skip it when the crowd is just background. The crowd's mood moves; it does not just gasp.",
        "travel" to "SCENE METHOD (travel and transitions): compress the trip, keep one strong place anchor and one physical sensation of moving through it, and arrive quickly.",
    )

    // ---- Step 3: record --------------------------------------------------------------------

    internal const val NOTE = "=== NOTE ==="
    internal const val SHEETS_MARK = "=== SHEETS ==="

    val RECORD = """
        You keep the memory of a web novel. You read ONE source chapter and record what it establishes, so later chapters stay consistent. Use only what the source states; invent nothing.

        Reply in exactly this form, nothing before or after:
        $NOTE
        ## Character knowledge changes
        - <who now knows, believes, has or has lost what>
        ## Obligations and promises
        - ...
        ## New facts
        - ...
        ## Open questions
        - ...
        $SHEETS_MARK
    """.trimIndent() + "\n" + SHEET_FORMAT.trimIndent()

    /** Reads an earlier chapter into memory, for a novel whose memory is still empty or behind. */
    val SHEETS = """
        You keep the memory of a web novel. You read ONE earlier chapter and extract its facts into the memory sheets. Do not rewrite or summarise beyond what is asked. Use only what the text states; invent nothing.

        Reply with sections only:
    """.trimIndent() + "\n" + SHEET_FORMAT.trimIndent()

    private const val SHEET_FORMAT = """
        ## people/<Name>
        - also called: <titles, nicknames, other spellings the text uses> (Ch N)
        - now: <realm or level, where they are, their condition, what they carry> (Ch N)
        - voice: <how they talk, described in your own words: register, confidence, how they address others, verbal habits; never quote their lines> (Ch N)
        - <appearance, role, trait, relation or what they know> (Ch N)
        ## places/<Name>
        - <layout, look, materials, light, sound, smell, landmarks, who is there> (Ch N)
        ## systems/<Name>
        - <ranks, rules, costs, currencies or techniques of the world, with the source's numbers> (Ch N)
        ## threads
        - <promise, open question, or the cause still pending behind an event> (Ch N)
        ## conflicts
        - <contradiction with earlier facts, or a line that does not sound like its speaker> (Ch N)
        ## synopsis
        - Ch N: <one sentence on what happened in this chapter>
        Rules: every bullet ends with the chapter citation from the message, like (Ch 12). Only recurring or consequential people, places and systems. One short bullet per fact; never repeat a fact in other words. Write a new "now:" line only when the state changed.
    """
}
