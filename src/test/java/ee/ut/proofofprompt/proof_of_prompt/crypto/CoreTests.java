package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole.*;
import static ee.ut.proofofprompt.proof_of_prompt.crypto.SiblingSide.*;

/** Dependency-free checks: failures throw and return nonzero, no -ea required. */
public final class CoreTests {
    private static int checks;
    private static final String[] TEXTS = {"Ты помощник службы поддержки.",
        "Возвращай деньги только после подтверждения сотрудника.",
        "Уточните номер заказа.", "Заказ A-17, верните деньги."};
    private static final MessageRole[] ROLES = {SYSTEM, DEVELOPER, ASSISTANT, USER};

    public static void main(String[] args) throws Exception {
        Properties vectors = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.US_ASCII)) {
            vectors.load(reader);
        }
        List<PromptMessage> lecture = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var m = message(i, ROLES[i], TEXTS[i]);
            lecture.add(m);
            equal(hex(CanonicalMessage.encode(m)), vectors.getProperty("canonical." + i), "canonical vector " + i);
            equal(m.commitmentHash(), vectors.getProperty("leaf." + i), "leaf vector " + i);
        }
        var tree = tree(lecture);
        equal(tree.rootHash(), vectors.getProperty("root.lecture"), "lecture root");
        equal(Hashing.node(lecture.get(0).commitmentHash(), lecture.get(1).commitmentHash()), vectors.getProperty("p01"), "P01");
        equal(Hashing.node(lecture.get(2).commitmentHash(), lecture.get(3).commitmentHash()), vectors.getProperty("p23"), "P23");
        equal(tree.proof(1).steps(), List.of(new ProofStep(vectors.getProperty("leaf.0"), LEFT),
                new ProofStep(vectors.getProperty("p23"), RIGHT)), "known proof position 1");
        System.out.println("PASS: canonical bytes, leaves, internal nodes, root and known path");

        for (int n = 1; n <= 65; n++) {
            List<PromptMessage> messages = new ArrayList<>();
            for (int i = 0; i < n; i++) messages.add(message(i, USER, "message-" + i));
            var current = tree(messages);
            equal(current.rootHash(), vectors.getProperty("root.size." + n), "independent root size " + n);
            for (int i = 0; i < n; i++) {
                var m = messages.get(i);
                var proof = current.proof(i);
                check(MerkleTree.verify(m, proof, current.rootHash(), n), "inclusion " + n + "/" + i);
                var changed = new PromptMessage(1, i, USER, m.content() + "!", m.randomValue());
                check(!MerkleTree.verify(changed, proof, current.rootHash(), n), "tamper " + n + "/" + i);
            }
        }
        System.out.println("PASS: all positions in tree sizes 1..65, including odd sizes");

        var original = lecture.get(1);
        var proof = tree.proof(1);
        reject(new PromptMessage(1, 1, USER, original.content(), original.randomValue()), proof, tree, "role");
        reject(new PromptMessage(1, 0, DEVELOPER, original.content(), original.randomValue()), proof, tree, "position");
        reject(new PromptMessage(1, 1, DEVELOPER, original.content(), "ff".repeat(32)), proof, tree, "secret");
        reject(new PromptMessage(1, 1, DEVELOPER, original.content().replace("только ", ""), original.randomValue()), proof, tree, "text");
        List<ProofStep> steps = new ArrayList<>(proof.steps());
        steps.set(0, new ProofStep("ff".repeat(32), LEFT));
        reject(original, new MerkleProof(steps), tree, "sibling hash");
        steps.set(0, new ProofStep(proof.steps().get(0).hash(), RIGHT));
        reject(original, new MerkleProof(steps), tree, "sibling side");
        steps = new ArrayList<>(proof.steps());
        Collections.reverse(steps);
        reject(original, new MerkleProof(steps), tree, "reversed path");
        reject(original, new MerkleProof(proof.steps().subList(0, 1)), tree, "short path");
        steps = new ArrayList<>(proof.steps());
        steps.add(proof.steps().get(0));
        reject(original, new MerkleProof(steps), tree, "long path");
        check(!MerkleTree.verify(original, proof, "ff".repeat(32), 4), "wrong expected root");
        check(!MerkleTree.verify(original, proof, tree.rootHash(), 5), "wrong expected count");
        // An opaque sibling can be a leaf or subtree. Authenticate the count
        // independently in the future signed receipt; do not infer it here.
        check(MerkleTree.verify(original, proof, tree.rootHash(), 3), "count needs independent authentication");
        check(!MerkleTree.verify(original, proof, tree.rootHash(), 1), "out of range position");
        check(!MerkleTree.verify(original, proof, tree.rootHash(), 0), "zero count");
        check(!MerkleTree.verify(original, proof, "bad", 4), "invalid root format");
        check(!MerkleTree.verify(null, proof, tree.rootHash(), 4), "null message");
        check(!MerkleTree.verify(original, null, tree.rootHash(), 4), "null proof");
        var swapped = new ArrayList<>(lecture);
        Collections.swap(swapped, 0, 1);
        check(!tree(swapped).rootHash().equals(tree.rootHash()), "ordered leaves");
        check(!Hashing.node(vectors.getProperty("leaf.0"), vectors.getProperty("leaf.1"))
                .equals(Hashing.node(vectors.getProperty("leaf.1"), vectors.getProperty("leaf.0"))), "ordered child hashes");
        System.out.println("PASS: tampered fields, paths, root, count and order rejected");

        throwsInvalid(() -> new PromptMessage(2, 0, USER, "x", "00".repeat(32)), "protocol version");
        throwsInvalid(() -> new PromptMessage(1, -1, USER, "x", "00".repeat(32)), "negative position");
        throwsInvalid(() -> new PromptMessage(1, 0, USER, "\uD800", "00".repeat(32)), "lone high surrogate");
        throwsInvalid(() -> new PromptMessage(1, 0, USER, "\uDC00", "00".repeat(32)), "lone low surrogate");
        throwsInvalid(() -> new PromptMessage(1, 0, null, "x", "00".repeat(32)), "null role");
        throwsInvalid(() -> new PromptMessage(1, 0, USER, null, "00".repeat(32)), "null content");
        for (String invalid : List.of("00", "AA".repeat(32), "0x" + "00".repeat(32), "gg".repeat(32))) {
            throwsInvalid(() -> new PromptMessage(1, 0, USER, "x", invalid), "invalid hex");
            throwsInvalid(() -> new ProofStep(invalid, LEFT), "invalid sibling hex");
        }
        throwsInvalid(() -> new ProofStep("00".repeat(32), null), "null side");
        throwsInvalid(() -> MessageRole.valueOf("user"), "role case");
        throwsInvalid(() -> SiblingSide.valueOf("left"), "side case");
        throwsInvalid(() -> new MerkleTree(List.of()), "empty tree");
        throwsInvalid(() -> new CommitmentService().commit(List.of()), "empty request");
        throwsInvalid(() -> tree.proof(-1), "negative proof index");
        throwsInvalid(() -> tree.proof(4), "proof index beyond tree");
        StringBuilder special = new StringBuilder();
        for (int i = 0; i < 32; i++) special.append((char) i);
        special.append("\"\\/Привет🙂\u2028\u2029e\u0301");
        equal(hex(CanonicalMessage.encode(new PromptMessage(1, 0, USER, special.toString(), "00".repeat(32)))),
                vectors.getProperty("canonical.special"), "JSON escaping and Unicode");
        check(!message(0, USER, "é").commitmentHash().equals(message(0, USER, "e\u0301").commitmentHash()), "no Unicode normalization");
        check(!message(0, USER, "x").commitmentHash().equals(message(0, USER, "x ").commitmentHash()), "no trimming");
        check(!message(0, USER, "x\n").commitmentHash().equals(message(0, USER, "x\r\n").commitmentHash()), "no newline conversion");
        var empty = message(0, USER, "");
        check(MerkleTree.verify(empty, tree(List.of(empty)).proof(0), empty.commitmentHash(), 1), "empty content allowed");
        check(tree(List.of(empty)).proof(0).steps().isEmpty(), "single leaf empty path");
        System.out.println("PASS: malformed input rejected; Unicode, escaping and whitespace preserved");

        var service = new CommitmentService();
        var input = List.of(new InputMessage(SYSTEM, "policy"), new InputMessage(USER, "private"));
        var first = service.commit(input);
        var second = service.commit(input);
        Set<String> secrets = new HashSet<>();
        for (var request : List.of(first, second)) for (var m : request.messages()) {
            check(m.randomValue().matches("[0-9a-f]{64}"), "secret format");
            check(secrets.add(m.randomValue()), "fresh secret smoke check");
        }
        check(!first.merkleRoot().equals(second.merkleRoot()), "new request new root");
        var disclosed = first.disclose(0);
        check(MerkleTree.verify(disclosed.message(), disclosed.proof(), first.merkleRoot(), first.messageCount()), "service round trip");
        check(!MerkleTree.verify(disclosed.message(), disclosed.proof(), second.merkleRoot(), second.messageCount()), "different request root");
        check(!first.messages().get(1).toString().contains("private"), "redacted logging");
        try { first.messages().clear(); throw new AssertionError("Mutable messages"); }
        catch (UnsupportedOperationException expected) { checks++; }
        var mutableSteps = new ArrayList<>(disclosed.proof().steps());
        var immutableProof = new MerkleProof(mutableSteps);
        mutableSteps.clear();
        check(!immutableProof.steps().isEmpty(), "proof defensive copy");
        System.out.println("PASS: service flow, secret freshness smoke checks and immutable data");
        System.out.println("ALL PASSED: " + checks + " checks");
    }

    private static PromptMessage message(int i, MessageRole role, String content) {
        return new PromptMessage(1, i, role, content, String.format(Locale.ROOT, "%02x", i + 1).repeat(32));
    }
    private static MerkleTree tree(List<PromptMessage> messages) {
        return new MerkleTree(messages.stream().map(PromptMessage::commitmentHash).toList());
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void reject(PromptMessage message, MerkleProof proof, MerkleTree tree, String name) {
        check(!MerkleTree.verify(message, proof, tree.rootHash(), tree.messageCount()), "reject " + name);
    }
    private static void equal(Object actual, Object expected, String name) {
        check(Objects.equals(actual, expected), name + ": expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
    private static void throwsInvalid(Runnable action, String name) {
        try { action.run(); }
        catch (IllegalArgumentException | NullPointerException expected) { checks++; return; }
        throw new AssertionError("Accepted invalid input: " + name);
    }
}
