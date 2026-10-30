package com.ersaz.hermesmobile.agent

/**
 * Prompt for the seeded "Friday design review" job (Fridays 09:00, same seeding style as
 * the Nightly learning job). The review is a real check, never a fake one: it must list
 * only screens and components it actually found in the current project, measured against
 * the bundled impeccable craft rules (spacing hierarchy, contrast, no purple/blue gradients,
 * no glow decoration, real copy, working back navigation). It posts a short brief in chat
 * and changes no files. If the project has web screens, it may open them in Hermes' own
 * Browser to check the layout — that is the Playwright-review concept, translated to the
 * phone's browser tools rather than desktop Playwright.
 */
object DesignReview {
    const val FRIDAY_DESIGN_REVIEW_PROMPT = """
You are reviewing the design of the user's current coding project (the project in settings).
Do a real review, not a generic one:
1. List the screens and components you actually found in the project's files (filenames, not invented names). If you find none, say so plainly and stop — do not invent a design to review.
2. Check what you found against these rules: clear spacing hierarchy (more space above a heading than below it), text contrast at least 4.5:1 for body text, no purple/blue gradients, no glow or glass as decoration, no filler copy, touch targets at least 44dp where the platform applies, and back navigation that works in every section.
3. If the project has web screens, you may open them in Hermes' Browser to check the layout as rendered; report only what you actually saw there.
4. Post a short brief in chat: what you checked, each real issue with the file it lives in, and one concrete fix per issue. Change no files.
"""
}
