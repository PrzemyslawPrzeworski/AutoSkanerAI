/**
 * The failure paths, replayed offline.
 *
 * The first live run of the tool loop crashed. `nvidia/nemotron-3-super-120b-a12b:free`
 * returned a review whose content was correct and whose JSON was not — a doubled opening
 * brace, `{\n{\n  "summary": …`. The SDK threw `NoObjectGeneratedError`, the guard in
 * `agent.ts` tested for `APICallError`, and the error escaped as an unhandled rejection.
 * Node printed it the way Node prints errors: the stack, and then every enumerable own
 * property. For an AI SDK error those properties are the entire request body, the whole
 * schema, the response headers including a `set-cookie` value, and the response text.
 *
 * The review is submitted as a tool call now rather than as structured output, so these
 * replays send `tool_calls` and the class that reports a broken shape is no longer
 * `NoObjectGeneratedError`. The failures themselves did not move: tool arguments are text
 * too, so a model that cannot frame JSON breaks here exactly as it broke there. What the
 * new channel adds is the case a response format made impossible — answering in prose and
 * never submitting at all.
 *
 * So these tests exist for two reasons, and the second is the important one:
 *
 *   1. A malformed answer must become a named error, not a crash — a crash exits 1, and
 *      1 is the code that means "this diff failed review".
 *   2. The escaping error must not be a container for the call. Every case below asserts on
 *      `util.inspect` of the error — what a printer sees, cause chain and all — not on its
 *      `.message`, because the message was never the vector.
 *
 * Reproducing this against the real endpoint would need a model to misbehave on demand.
 * A local server replaying the recorded response gets the same code path for free, in
 * milliseconds, deterministically — and keeps it a regression test rather than an anecdote.
 */
import assert from 'node:assert/strict';
import { createServer, type Server } from 'node:http';
import { type AddressInfo } from 'node:net';
import { after, test } from 'node:test';
import { inspect } from 'node:util';
import { reviewDiff } from './agent.ts';

const DIFF = [
  'diff --git a/backend/src/main/java/A.java b/backend/src/main/java/A.java',
  '--- a/backend/src/main/java/A.java',
  '+++ b/backend/src/main/java/A.java',
  '@@ -1,1 +1,2 @@',
  ' class A {}',
  '+// SENTINEL_DIFF_CONTENT',
].join('\n');

/** A recognisable stand-in for the live key, so a leak is greppable rather than guessed at. */
const FAKE_KEY = 'sk-or-v1-SENTINEL_KEY_MUST_NOT_APPEAR';

const servers: Server[] = [];

after(() => {
  for (const server of servers) server.close();
});

/**
 * A server that answers every chat completion the same way. `body` is what the model
 * "said"; `status` lets a test be a provider refusal instead.
 */
async function replay(body: string, status = 200): Promise<string> {
  const server = createServer((request, response) => {
    // Drain the request: the SDK sends a body, and an unread one stalls the socket.
    request.resume();
    request.on('end', () => {
      response.writeHead(status, {
        'content-type': 'application/json',
        // The header that made the original leak a credential leak rather than only noise.
        'set-cookie': '__cf_bm=SENTINEL_COOKIE_MUST_NOT_APPEAR; path=/',
      });
      response.end(body);
    });
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  return `http://127.0.0.1:${port}`;
}

function completion(content: string): string {
  return JSON.stringify({
    id: 'replay-1',
    object: 'chat.completion',
    created: 1,
    model: 'replay/model',
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 11, completion_tokens: 22, total_tokens: 33 },
  });
}

/**
 * The model answering the way the reviewer now asks it to: a `submitReview` call whose
 * arguments are the review.
 *
 * `args` is a raw string, not an object, and that is the whole point of this helper —
 * the arguments of a tool call travel as text, so every way a model can break JSON is
 * still available to it here. That is where the recorded failures below live.
 */
function submitCall(args: string): string {
  return JSON.stringify({
    id: 'replay-1',
    object: 'chat.completion',
    created: 1,
    model: 'replay/model',
    choices: [
      {
        index: 0,
        message: {
          role: 'assistant',
          content: null,
          tool_calls: [
            {
              id: 'call_1',
              type: 'function',
              function: { name: 'submitReview', arguments: args },
            },
          ],
        },
        finish_reason: 'tool_calls',
      },
    ],
    usage: { prompt_tokens: 11, completion_tokens: 22, total_tokens: 33 },
  });
}

async function reviewAgainst(baseURL: string) {
  return reviewDiff(DIFF, { baseURL, apiKey: FAKE_KEY, modelId: 'replay/model' });
}

/**
 * Checks the error against everything the original unhandled rejection printed.
 *
 * It inspects `util.inspect(error, { depth: 8 })`, NOT `error.message`, and the difference
 * is the whole value of this helper. The first draft checked the message, and passed with
 * both guards in `agent.ts` deliberately disabled — because the message was never the leak
 * vector. Node's error printer appends an error's enumerable own properties and follows its
 * `[cause]` chain, so an error whose message is two lines still prints the request body if
 * it carries the SDK error underneath. `depth: 8` is deep enough to reach through a cause
 * chain that a future refactor might reintroduce.
 */
function assertNothingLeaked(error: unknown): void {
  const printed = inspect(error, { depth: 8 });
  assert.doesNotMatch(printed, /SENTINEL_KEY_MUST_NOT_APPEAR/, 'the API key is reachable from the error');
  assert.doesNotMatch(printed, /SENTINEL_COOKIE_MUST_NOT_APPEAR/, 'a set-cookie value is reachable');
  assert.doesNotMatch(printed, /SENTINEL_DIFF_CONTENT/, 'the request body is reachable from the error');
  assert.doesNotMatch(printed, /inputSchema|jsonSchema|"?\$schema"?\s*:/i, 'the JSON schema is reachable');
  assert.ok(error instanceof Error);
  assert.ok(
    error.message.length < 600,
    `a failure message of ${error.message.length} chars is a dump, not a message`,
  );
}

test('the harness is faithful: a well-formed replay produces a real review', async () => {
  // Without this, every test below could be passing because the fake server is broken in
  // some way that has nothing to do with the code under test.
  const baseURL = await replay(
    submitCall(
      JSON.stringify({
        summary: 'one finding',
        findings: [
          {
            file: 'backend/src/main/java/A.java',
            severity: 'major',
            summary: 's',
            rationale: 'r',
          },
        ],
      }),
    ),
  );

  const { review, usage, steps } = await reviewAgainst(baseURL);
  assert.equal(review.verdict, 'fail');
  assert.equal(review.findings.length, 1);
  assert.equal(usage.totalTokens, 33);
  // Also asserts the stop condition fires. This server answers every request with the
  // same submitReview call, so without `hasToolCall` the loop would run to the step
  // budget and this would read 8.
  assert.equal(steps, 1);
});

test('the recorded doubled brace becomes malformed-output, not a crash', async () => {
  // Verbatim shape of the live failure: a leading `{`, a newline, then the real object.
  // It arrived as response text when the schema was a response format and arrives as tool
  // arguments now; a model that cannot frame JSON cannot frame it in either channel.
  const baseURL = await replay(
    submitCall('{\n' + JSON.stringify({ summary: 'correct content, broken frame', findings: [] })),
  );

  await assert.rejects(reviewAgainst(baseURL), (error: unknown) => {
    assert.ok(error instanceof Error);
    assert.equal((error as { kind?: string }).kind, 'malformed-output');
    assertNothingLeaked(error);
    // The snippet is the point of the message: without it the operator cannot tell a
    // doubled brace from a truncation from prose wrapped around the object.
    assert.match(error.message, /First 120 chars: "\{/);
    return true;
  });
});

test('a long malformed answer is summarised, not reprinted', async () => {
  // The case the first fix missed, and the reason the doubled-brace test above did not
  // catch it: that replay is 80 characters long, so an unbounded message still looked
  // bounded. The real run printed all 1.7 KB of the model's answer, because the parse
  // error's message quotes the entire text it could not parse — and the wrapper
  // interpolated that message verbatim. `InvalidToolInputError` has the same property:
  // its own message is `Invalid input for tool submitReview: <the whole cause>`.
  const long = '{\n' + JSON.stringify({ summary: 'x'.repeat(4000), findings: [] });
  const baseURL = await replay(submitCall(long));

  await assert.rejects(reviewAgainst(baseURL), (error: unknown) => {
    assert.ok(error instanceof Error);
    assert.equal((error as { kind?: string }).kind, 'malformed-output');
    // assertNothingLeaked caps at 600; assert the tighter real bound too, so a regression
    // that merely halves the dump does not slip through.
    assert.ok(
      error.message.length < 400,
      `message was ${error.message.length} chars for a ${long.length}-char answer`,
    );
    assert.doesNotMatch(error.message, /x{200}/, 'the model answer was reprinted into the message');
    // The diagnosis survives the truncation — that is the point of walking to the
    // deepest cause rather than just slicing the top-level message.
    assert.match(error.message, /SyntaxError/);
    assertNothingLeaked(error);
    return true;
  });
});

test('a server that accepts and never answers becomes timeout, not a hang', async () => {
  // The observed failure, reproduced exactly: the connection is accepted, the request is
  // read, and no response is ever written. A `:free` slug did this for over ten minutes.
  // Nothing in this package bounded it, so the only limit was how long someone waited —
  // and Phase 5 turns that someone into a git hook.
  const server = createServer((request) => {
    request.resume();
    // No response. Deliberately.
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;

  const started = Date.now();
  await assert.rejects(
    reviewDiff(DIFF, {
      baseURL: `http://127.0.0.1:${port}`,
      apiKey: FAKE_KEY,
      modelId: 'replay/model',
      timeoutMs: 400,
    }),
    (error: unknown) => {
      assert.equal((error as { kind?: string }).kind, 'timeout');
      assertNothingLeaked(error);
      return true;
    },
  );
  // Asserts the budget is actually enforced rather than merely declared: without the
  // abortSignal this rejects only when the socket eventually gives up, if ever.
  const elapsed = Date.now() - started;
  assert.ok(elapsed < 15_000, `the 400 ms budget took ${elapsed} ms to enforce`);
});

test('a provider refusal becomes provider, and does not carry the request', async () => {
  // 402 rather than 500: the SDK retries 5xx, and a retry loop here would test patience.
  const baseURL = await replay(
    JSON.stringify({ error: { message: 'This model is unavailable for free', code: 402 } }),
    402,
  );

  await assert.rejects(reviewAgainst(baseURL), (error: unknown) => {
    assert.ok(error instanceof Error);
    assert.equal((error as { kind?: string }).kind, 'provider');
    assert.match(error.message, /HTTP 402/);
    assertNothingLeaked(error);
    return true;
  });
});

test('a review written as prose instead of submitted is no-output, not a pass', async () => {
  // The failure mode the tool-call design introduces, and the one that matters most: with
  // no `response_format` in the request, nothing stops a model from writing its review as
  // text. It looks like a completed review — a perfectly good one, here — and the reviewer
  // has nothing it can act on. The only wrong answer would be to treat the empty result as
  // an approval, which is what "no findings parsed" would silently mean.
  const baseURL = await replay(
    completion(
      'Sure! Here is the review you asked for:\n\n```json\n' +
        JSON.stringify({ summary: 's', findings: [] }) +
        '\n```',
    ),
  );

  await assert.rejects(reviewAgainst(baseURL), (error: unknown) => {
    assert.equal((error as { kind?: string }).kind, 'no-output');
    assert.ok(error instanceof Error);
    assert.match(error.message, /submitReview/);
    assertNothingLeaked(error);
    return true;
  });
});

test('a submitted review missing a schema-required field is reported, not silently emptied', async () => {
  // Valid JSON, wrong shape: `findings` absent entirely. The danger here is the opposite of
  // a crash — a schema failure that resolved to `{findings: []}` would read as a clean pass.
  // This is also the case that proves the schema is still enforced now that it is a tool's
  // inputSchema rather than a response format: the SDK validates tool input before running
  // `execute`, so a wrong shape never reaches the review.
  const baseURL = await replay(submitCall(JSON.stringify({ summary: 'no findings key' })));

  await assert.rejects(reviewAgainst(baseURL), (error: unknown) => {
    assert.ok(error instanceof Error);
    assert.equal((error as { kind?: string }).kind, 'malformed-output');
    // Names the offending field. Without this the message read `ZodError: [` — technically
    // a correct diagnosis, useless to act on — because the deepest cause here is a Zod
    // error whose message is a pretty-printed array and whose first line is a bracket.
    assert.match(error.message, /findings/);
    assertNothingLeaked(error);
    return true;
  });
});
