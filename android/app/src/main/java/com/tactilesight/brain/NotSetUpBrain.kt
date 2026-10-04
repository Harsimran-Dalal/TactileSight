package com.tactilesight.brain

import com.tactilesight.core.Answer
import com.tactilesight.core.Frame
import com.tactilesight.core.SemanticBrain

/**
 * What answers when there is nothing to describe with: no model on the phone,
 * or the cloud chosen with no key.
 *
 * Replaces [StubBrain] in that role. The stub's fixed sentence — "a clear path
 * ahead, with a doorway on the right" — was fine while only the team installed
 * the app and knew it for a placeholder. On a stranger's phone it is a
 * confident description of a room nobody looked at, spoken to someone who
 * cannot check it. Every press still yields speech (hard rule #4); this speech
 * says what to do instead of inventing a scene.
 */
class NotSetUpBrain(private val spokenMessage: String) : SemanticBrain {

    override val name: String = "Not set up"

    override suspend fun describe(frame: Frame, question: String?, surfaceIsFlat: Boolean): Answer =
        Answer(spokenMessage, confident = false)
}
