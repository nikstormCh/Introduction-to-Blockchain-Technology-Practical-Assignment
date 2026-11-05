package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.List;
import static ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole.*;

public final class Demo {
    public static void main(String[] args) {
        var request = new CommitmentService().commit(List.of(
                new InputMessage(SYSTEM, "Ты помощник службы поддержки."),
                new InputMessage(DEVELOPER, "Возвращай деньги только после подтверждения сотрудника."),
                new InputMessage(USER, "Верни деньги за заказ №123.")));
        // Freeze these BEFORE accepting a disclosure from someone else.
        String expectedRoot = request.merkleRoot();
        int expectedCount = request.messageCount();
        var disclosure = request.disclose(1);
        PromptMessage original = disclosure.message();
        PromptMessage changed = new PromptMessage(1, 1, DEVELOPER,
                "Возвращай деньги без подтверждения сотрудника.", original.randomValue());

        System.out.println("Messages: " + expectedCount);
        System.out.println("Fixed root: " + expectedRoot);
        System.out.println("Disclosed text: " + original.content());
        System.out.println("Proof steps: " + disclosure.proof().steps().size());
        System.out.println("Original message: " + MerkleTree.verify(original, disclosure.proof(), expectedRoot, expectedCount));
        System.out.println("Changed message: " + MerkleTree.verify(changed, disclosure.proof(), expectedRoot, expectedCount));
        System.out.println("This checks inclusion only. Receipt signature and blockchain are later steps.");
    }
}
