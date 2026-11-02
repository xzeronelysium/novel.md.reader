package com.mdreader.data

/**
 * Every prompt the rewrite uses, in one place. They are fixed: the pipeline fills in the data
 * (source, memory, plan) but never changes the instructions. Bump [RewriteClient.PROMPT_VERSION]
 * whenever any text here changes.
 */
object RewritePrompts {

    /** Words that make prose ornate or tired. The prompt bans them and the code checks for them. */
    val BANNED = listOf(
        "ephemeral", "enigmatic", "palpable", "tapestry", "testament to", "symphony of", "couldn't help but",
        "a mix of", "orbs", "a sense of", "it was as if", "eyes glinted", "intricate", "myriad", "unwavering",
        "a wave of", "sent shivers", "the weight of", "visceral", "ethereal", "resolute", "serene",
        "effortless grace", "in that moment", "a flicker of", "steely", "ministrations", "a beat of silence",
    )

    // ---- Step 1: analyse -------------------------------------------------------------------

    internal const val BEATS = "=== BEATS ==="
    internal const val SCENES = "=== SCENES ==="
    internal const val FLAGS = "=== FLAGS ==="

    val ANALYSE = """
        You plan the rewrite of ONE chapter of a web novel. You do not write prose. The source is split into numbered paragraphs [P1], [P2], ...

        Do three things:

        1. BEATS. List every beat of the source in order: each thing that happens, is said, is decided, is felt or is learned, including physical and bodily details and small emotional turns ("he is so pleased he forgets to be disappointed"). One line each, with the paragraph range and the cause. The cause comes from the source, from the memory, or is "unknown". Never invent a cause.

        2. SCENES. Split the chapter into scenes of roughly 300 to 900 source words, at natural turns (place, time, focus or mood changes). Scenes cover every paragraph exactly once, in order. For each scene give:
           - type: one of action, dialogue, inner, comedy, system, crowd, travel
           - register: the feeling to amplify (for example comic, grim, tense, tender, awe, eerie)
           - texture: 3 specific sensory or physical details to add, consistent with the memory and the source
           - sharpen: one exchange or moment to make press harder
           - anchor: one concrete place detail (from the memory if it holds one)

        3. FLAGS. Lines that do not fit the speaker's recorded voice (with a hypothesis: lying, disguised, under stress, or someone else), and contradictions between the source and the memory. Write "- none" if there are none.

        Reply in exactly this form, nothing before or after:
        $BEATS
        1. [P1-P2] <beat> | cause: <cause>
        $SCENES
        S1 | P1-P9 | type: <type> | register: <register> | texture: <a>; <b>; <c> | sharpen: <moment> | anchor: <detail>
        $FLAGS
        - <flag>
    """.trimIndent()

    // ---- Step 2: write, one scene at a time --------------------------------------------------

    val WRITE = """
        You are a novelist rewriting ONE scene of a web novel so it reads as the best version of itself. Same events, far better telling. Reply with the scene's prose in Markdown and nothing else: no heading, no notes, no commentary.

        CANON, never changed
        - Every beat in the list you are given happens, in that order, with the same outcome. Keep every physical detail and every emotional turn as the source states it. If the source says he forgets to be disappointed, he forgets: do not turn a non-event into an event, or an event into a non-event.
        - Names, ranks, abilities, numbers, items, system text and every line of dialogue's meaning stay as the source has them. Do not invent names, titles, techniques, nicknames or labels for things the source does not name.
        - Nothing from later chapters. Ambiguity stays ambiguous.

        TEXTURE, yours to add
        - Unnamed bystanders and what they do, sensory detail, gestures, small physical business, ambient sound, offhand remarks, the look and feel of a place. Texture never changes what happens later.

        THE LAWS
        - Causality: show why things happen, using the causes you are given. If a cause is unknown, do not make one up.
        - Continuity: people know only what they have learned; places, wounds and objects stay as the memory left them.
        - Voice: each speaker talks the way the memory records them.
        - Proportion: effort, pain and cost show up in the body and in later lines.

        CRAFT
        - Amplify the register you are given. Comic scenes get funnier, grim scenes heavier, tense scenes tighter.
        - Emotion is shown through the body and through choices, never named ("he was furious" is banned).
        - Every line of dialogue pushes, dodges, tests or hides something.
        - Crowds and minor characters each get a small, specific moment. Never two identical reactions in a row.
        - Violence and gore at full weight, in plain exact words. Never softened, skipped or summarised.
        - Use the texture, sharpen and anchor notes you are given.

        STYLE
        - Everyday words. Short, clear sentences, with a short one for impact. Rich, never ornate.
        - Paragraphs of 1 to 4 sentences, for reading on a phone.
        - Dialogue tags: mostly "said"; often no tag at all, with an action beat instead.
        - Numbers as digits (5 swords, 3,000 gold, a 10-year-old boy), keeping the source's values.
        - Never call a person by the same description twice ("the older soldier", "the chubby boy"). Use the name, then a pronoun, then something they are doing.
        FORMATTING. The reader draws each kind of content its own way, so give every kind its exact form:
        - System notices, status screens, stats, skills, items, quests and level-ups: every line as a bold blockquote in the source's own words (keep its brackets; a notice the source wraps over several lines gets one pair of brackets per line), one line per entry, with no blank line between the lines of one screen.
          > **[Skill Name: Invisibility]**
          > **[Cost: 8000 skill points]**
          A stats table the source prints as "Key: Value" lines is a status screen too: > **Physique: Heaven Refining Physique**
        - Chat, group chat, text messages and transmissions shown as a log: one message per line, sender in bold followed by a colon, message in curly quotes, consecutive lines with no blank line between them. A notice in the log (like a member count) is a plain line in the same block.
          > **Fang Yun:** “Heh, you figured it out pretty quickly.”
          > **Wang Ping:** “Why do you say that?”
          > Group members: 7/67.
        - Letters, notes, signs, inscriptions and words read or remembered verbatim: a plain blockquote, never bold.
          > Feed them once. Do not talk to the boy.
        - Thoughts, sounds and the rare stressed word: *italics*. Never bold in the prose itself.
        - A line with only --- where the source breaks the scene, or where the story jumps in time or place. Never between two moments that run on.
        - Spoken dialogue in the story is ordinary prose: each speaker's line starts its own paragraph, with curly quotes and em dashes.
        - No headings, lists, tables, code blocks or HTML.
        - Never use these words or phrases: ${BANNED.joinToString(", ")}.
        - Drop translator notes, author notes, footnote markers, promo lines and site watermarks.

        EXAMPLES OF THE TASTE (show the method; never reuse the wording)
        Flat: "The crowd gasped when the door burst open."
        Alive: "The door burst open and the hall went quiet in pieces: first the dice, then the talk, then a boy by the stove who had been mid-laugh and now only breathed through his open mouth. Somebody's cup rolled off a table. Nobody went after it."
        Flat: "He was furious."
        Alive: "He set the cup down very carefully, the way you set down something you want to throw."
        Flat: "The young master boasted about his skill."
        Alive: "The young master announced his skill the way other men announce a funeral: slowly, so everyone had time to arrange their faces."
        Flat: "The cellar was dark and damp."
        Alive: "The cellar smelled of wet stone and old iron. Water ticked somewhere behind the wall."
        Flat: "The blade fell and he died."
        Alive: "The blade came down with a sound like a cleaver through a melon. The head hit the boards, rolled once, and stopped facing the door. For a moment the body stayed kneeling, as if no one had told it."
        Flat: "He thought about how formations were ranked."
        Alive: "He traced the second line again. One pattern made an apprentice. Six made a Formation Master. Nine, and the Taoist Court would put your name on a list. His finger stopped at two."

        LENGTH IS A MINIMUM. The message gives the minimum and maximum words for this scene. Reach the minimum with real texture on the beats that matter, never with filler, repetition or summary.
    """.trimIndent()

    /** How each kind of scene is enhanced; only the scene's own method is sent. */
    val METHODS = mapOf(
        "action" to "SCENE METHOD (action): blow by blow, in the order it happens. Each move has weight, sound and a body cost. Let space and footing matter. After the violence, show what it cost: breath, shaking hands, blood, silence.",
        "dialogue" to "SCENE METHOD (dialogue): give each speaker a goal and a tell. Cut lines that only relay what both already know into action beats or glances. Let silences and interruptions carry pressure. Keep every piece of information that passes between them.",
        "inner" to "SCENE METHOD (inner thought and explanation): do not paraphrase a block of thinking. Put the character in the present moment, doing something with their hands or body, and let each fact arrive as something they notice, remember, test or decide. Keep every fact and every number of the explanation.",
        "comedy" to "SCENE METHOD (comedy): set up, then a specific absurd detail, then an undercut, then a beat for the reaction. Timing matters more than adjectives. Let the straight man stay straight.",
        "system" to "SCENE METHOD (system and status screens): keep each screen verbatim as a blockquote. The feeling lives in the character's reaction before and after: what they expected, what they see, what they do next.",
        "crowd" to "SCENE METHOD (crowds and groups): pick 2 or 3 individuals in the crowd and give each one small, specific action or line. The crowd's mood moves; it does not just gasp.",
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
        - voice: <how they talk: register, how they address others, habits> "<a short line they said>" (Ch N)
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
