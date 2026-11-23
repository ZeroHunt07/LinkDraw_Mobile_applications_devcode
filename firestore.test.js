const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(offsetMs = 1000) {
  return new Date(Date.now() - offsetMs);
}

async function seedBoardWithInvite(boardId = "board_1", inviteCode = "LD7K9M") {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const ts = pastDate(5000);
    await db
      .collection("collab_boards")
      .doc(boardId)
      .set({
        ownerId: ALICE_UID,
        ownerName: "Alice",
        title: "Shared Threat Graph",
        description: "Live collaboration test",
        inviteCode,
        memberUids: [ALICE_UID],
        memberNames: { [ALICE_UID]: "Alice" },
        gridSnap: true,
        gridSize: 24,
        flowAnimation: false,
        createdAt: ts,
        updatedAt: ts,
      });
    await db
      .collection("board_invites")
      .doc(inviteCode)
      .set({
        boardId,
        ownerId: ALICE_UID,
        title: "Shared Threat Graph",
        createdAt: ts,
      });
  });
}

test("Unauthenticated users are blocked from all collections", async () => {
  await seedBoardWithInvite();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb
      .collection("collab_boards")
      .where("memberUids", "==", [ALICE_UID])
      .get()
  );
  await assertFails(unauthDb.collection("collab_boards").doc("board_1").get());
  await assertFails(unauthDb.collection("board_invites").doc("LD7K9M").get());
  await assertFails(
    unauthDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("elements")
      .get()
  );
});

test("Owner can atomically create collab board + invite, and query memberUids", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate(1000);
  const batch = aliceDb.batch();

  const boardRef = aliceDb.collection("collab_boards").doc("board_alpha");
  batch.set(boardRef, {
    ownerId: ALICE_UID,
    ownerName: "Alice",
    title: "Architecture Blueprint",
    description: "Microservices",
    inviteCode: "ABC123",
    memberUids: [ALICE_UID],
    memberNames: { [ALICE_UID]: "Alice" },
    gridSnap: true,
    gridSize: 24,
    flowAnimation: false,
    createdAt: ts,
    updatedAt: ts,
  });

  const inviteRef = aliceDb.collection("board_invites").doc("ABC123");
  batch.set(inviteRef, {
    boardId: "board_alpha",
    ownerId: ALICE_UID,
    title: "Architecture Blueprint",
    createdAt: ts,
  });

  await assertSucceeds(batch.commit());

  // Aligned list query succeeds
  await assertSucceeds(
    aliceDb
      .collection("collab_boards")
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );

  // Unfiltered list query fails
  await assertFails(aliceDb.collection("collab_boards").get());
  // Listing invites is always forbidden
  await assertFails(aliceDb.collection("board_invites").get());
});

test("Rejects @DocumentId 'id' leakage, ghost fields, future timestamps, and ownerId spoofing", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = pastDate(1000);

  await assertFails(
    aliceDb.collection("collab_boards").doc("bad_id").set({
      id: "bad_id",
      ownerId: ALICE_UID,
      ownerName: "Alice",
      title: "Bad",
      description: "",
      inviteCode: "BAD123",
      memberUids: [ALICE_UID],
      memberNames: { [ALICE_UID]: "Alice" },
      gridSnap: true,
      gridSize: 24,
      flowAnimation: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("collab_boards").doc("spoof").set({
      ownerId: BOB_UID,
      ownerName: "Bob",
      title: "Spoof",
      description: "",
      inviteCode: "SPF123",
      memberUids: [BOB_UID],
      memberNames: { [BOB_UID]: "Bob" },
      gridSnap: true,
      gridSize: 24,
      flowAnimation: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("collab_boards").doc("future_ts").set({
      ownerId: ALICE_UID,
      ownerName: "Alice",
      title: "Future",
      description: "",
      inviteCode: "FUT123",
      memberUids: [ALICE_UID],
      memberNames: { [ALICE_UID]: "Alice" },
      gridSnap: true,
      gridSize: 24,
      flowAnimation: false,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: ts,
    })
  );
});

test("Non-member cannot read board or subcollections; joining via valid invite code grants access", async () => {
  await seedBoardWithInvite("board_1", "LD7K9M");
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Denied before joining
  await assertFails(bobDb.collection("collab_boards").doc("board_1").get());
  await assertFails(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("elements")
      .get()
  );

  // Can fetch exact invite doc by code
  await assertSucceeds(bobDb.collection("board_invites").doc("LD7K9M").get());

  // Join board cleanly updating only memberUids, memberNames.<uid>, updatedAt
  await assertSucceeds(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .update({
        memberUids: [ALICE_UID, BOB_UID],
        "memberNames.bob_456": "Bob",
        updatedAt: pastDate(500),
      })
  );

  // Cannot hijack title or overwrite Alice's memberName during join
  await assertFails(
    testEnv
      .authenticatedContext(CHARLIE_UID)
      .firestore()
      .collection("collab_boards")
      .doc("board_1")
      .update({
        memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
        "memberNames.alice_123": "Hacked",
        updatedAt: pastDate(400),
      })
  );

  // Now Bob can read board and write elements, presence, and comments
  await assertSucceeds(bobDb.collection("collab_boards").doc("board_1").get());
  const ts = pastDate(300);

  await assertSucceeds(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("elements")
      .doc("n_1")
      .set({
        kind: "node",
        payload: { x: 100, y: 120, text: "Gateway", shape: "rounded" },
        updatedByUid: BOB_UID,
        updatedByName: "Bob",
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Partial update on element succeeds and preserves createdAt
  await assertSucceeds(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("elements")
      .doc("n_1")
      .update({
        payload: { x: 180, y: 200, text: "Gateway", shape: "rounded" },
        updatedByUid: BOB_UID,
        updatedByName: "Bob",
        updatedAt: pastDate(100),
      })
  );

  // Immutable createdAt mutation fails
  await assertFails(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("elements")
      .doc("n_1")
      .update({
        createdAt: pastDate(50),
        updatedAt: pastDate(50),
      })
  );

  // Presence & comment writes by Bob succeed
  await assertSucceeds(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("presence")
      .doc(BOB_UID)
      .set({
        uid: BOB_UID,
        displayName: "Bob",
        colorHex: "#2563EB",
        selectedNodeId: "n_1",
        activeTab: "canvas",
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Bob cannot spoof Alice's presence
  await assertFails(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("presence")
      .doc(ALICE_UID)
      .set({
        uid: ALICE_UID,
        displayName: "FakeAlice",
        colorHex: "#2563EB",
        activeTab: "canvas",
        createdAt: ts,
        updatedAt: ts,
      })
  );

  await assertSucceeds(
    bobDb
      .collection("collab_boards")
      .doc("board_1")
      .collection("comments")
      .doc("c_1")
      .set({
        authorId: BOB_UID,
        authorName: "Bob",
        text: "Updated gateway node position",
        linkedNodeId: "n_1",
        linkedNodeLabel: "Gateway",
        createdAt: ts,
        updatedAt: ts,
      })
  );
});

test("Forward-compatible Web extensions, custom element kinds, and presence cursors are allowed for members and blocked for non-members", async () => {
  await seedBoardWithInvite("board_ext", "WEB999");
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(200);

  // Member can write a Web-only element kind with schemaVersion and sourcePlatform
  await assertSucceeds(
    aliceDb
      .collection("collab_boards")
      .doc("board_ext")
      .collection("elements")
      .doc("frame_1")
      .set({
        kind: "frame",
        payload: { x: 400, y: 200, w: 320, h: 240, title: "DMZ Zone", opacity: 0.5 },
        updatedByUid: ALICE_UID,
        updatedByName: "Alice",
        schemaVersion: 2,
        sourcePlatform: "web",
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Member can update board extensions and float gridSize
  await assertSucceeds(
    aliceDb
      .collection("collab_boards")
      .doc("board_ext")
      .update({
        gridSize: 24.0,
        extensions: { theme: "cyber", timelineEnabled: true },
        schemaVersion: 2,
        sourcePlatform: "web",
        updatedAt: pastDate(100),
      })
  );

  // Member can publish live cursor + custom activeTab in presence
  await assertSucceeds(
    aliceDb
      .collection("collab_boards")
      .doc("board_ext")
      .collection("presence")
      .doc(ALICE_UID)
      .set({
        uid: ALICE_UID,
        displayName: "Alice",
        colorHex: "#2563EB",
        activeTab: "timeline",
        cursorX: 125.5,
        cursorY: -40.25,
        viewportZoom: 1.25,
        sourcePlatform: "web",
        createdAt: ts,
        updatedAt: ts,
      })
  );

  // Non-member is still denied from writing custom element kinds
  await assertFails(
    bobDb
      .collection("collab_boards")
      .doc("board_ext")
      .collection("elements")
      .doc("frame_2")
      .set({
        kind: "frame",
        payload: { x: 0, y: 0 },
        updatedByUid: BOB_UID,
        updatedByName: "Bob",
        createdAt: ts,
        updatedAt: ts,
      })
  );
});

test("Personal user notes and shared board notes enforce owner/member isolation", async () => {
  await seedBoardWithInvite("board_notes", "NTE123");
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(200);

  const notePayload = (uid, name) => ({
    title: "Threat Brief",
    body: "## IOC Summary\n- Checked ASN",
    noteType: "Analysis",
    folder: "General",
    tags: ["osint"],
    priority: "high",
    colorHex: "#EEF2FF",
    pinned: true,
    archived: false,
    sourceUrl: "",
    checklist: [{ id: "c1", text: "Verify WHOIS", checked: true }],
    wordCount: 5,
    updatedByUid: uid,
    updatedByName: name,
    schemaVersion: 1,
    sourcePlatform: "android",
    createdAt: ts,
    updatedAt: ts,
  });

  // Owner can write and read personal cloud notes
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1").set(notePayload(ALICE_UID, "Alice"))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1").get()
  );

  // Another user cannot read or write Alice's personal notes
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("notes").doc("note_2").set(notePayload(BOB_UID, "Bob"))
  );

  // Board member Alice can sync notes to shared board; non-member Bob is blocked
  await assertSucceeds(
    aliceDb.collection("collab_boards").doc("board_notes").collection("notes").doc("bnote_1").set(notePayload(ALICE_UID, "Alice"))
  );
  await assertFails(
    bobDb.collection("collab_boards").doc("board_notes").collection("notes").doc("bnote_1").get()
  );
});


