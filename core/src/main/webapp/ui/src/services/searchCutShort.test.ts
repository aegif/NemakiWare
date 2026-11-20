import { it, expect, vi } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { CMISService } from './cmis';
import { isCutShortByAclScanLimit, SearchCutShortError, CUT_SHORT_MARK } from './searchCutShort';

// The search answers as the CMIS Browser binding writes them, written by the Browser binding's own query operation over the
// marks of the product's own producer (core's TheBrowserBindingWritesTheCutShortMarkAsTheUiReadsItTest holds this file
// against the serializer). R344, 3.4.2: a marked answer is not the result of the search — not its rows, count or "more".
// The test names are single words because the negative-control runner reads them as lock names.
const FIXTURE: Record<string, any> = JSON.parse(
  readFileSync(resolve(__dirname, '__fixtures__/browserQueryCutShort.json'), 'utf-8')
);
const MARKED = ['markedWithMoreInsideTheWindow', 'markedAtTheEndOfTheWindow', 'markedWithNoRow', 'markedWithTheCountOfItsRows',
  'markedAfterAFalseElement'];

function serviceAnswering(answer: unknown): CMISService {
  const service = new CMISService();
  (service as any).httpClient = {
    postUrlEncoded: vi.fn().mockResolvedValue({
      status: 200, statusText: 'OK', responseURL: '', responseText: JSON.stringify(answer),
    }),
  };
  return service;
}

it('theFixtureCarriesTheMarkAsTheSerializerWroteIt', () => {
  // A guard on the fixture itself: the mark is a top-level string beside the answer's own keys.
  expect(FIXTURE.markedWithMoreInsideTheWindow[CUT_SHORT_MARK]).toBe('true');
  expect(FIXTURE.markedWithMoreInsideTheWindow.results).toHaveLength(2);
  expect(FIXTURE.markedAfterAFalseElement[CUT_SHORT_MARK]).toEqual(['false', 'true']);
  expect(FIXTURE.unmarked[CUT_SHORT_MARK]).toBeUndefined();
  expect(FIXTURE.markedFalse[CUT_SHORT_MARK]).toBe('false');
});

it('searchRefusesEveryCutShortAnswer', async () => {
  for (const name of MARKED) {
    // The outcome is asserted, not thrown: a search that answered fails this lock's own expect().
    const refusal = await serviceAnswering(FIXTURE[name]).search('bedroom', 'SELECT * FROM cmis:document').then(
      () => `${name} was answered as a result`,
      (error: unknown) => error,
    );
    expect(refusal, name).toBeInstanceOf(SearchCutShortError);
    const text = String((refusal as Error).message);
    for (const leak of ['visible-row-name-', 'visible-row-id-', '7300', '7140']) {
      expect(text.includes(leak), `${name}: the refusal carries ${leak}`).toBe(false);
    }
  }
});

/** The search's answer, or its refusal as a value — so a refusal fails this lock's own expect(), not the harness. */
async function answered(answer: unknown) {
  const outcome = await serviceAnswering(answer).search('bedroom', 'SELECT * FROM cmis:document').catch((error: unknown) => error);
  expect(outcome, 'the search was refused').not.toBeInstanceOf(Error);
  return outcome as Awaited<ReturnType<CMISService['search']>>;
}

it('searchAnswersAnUnmarkedAnswerAsBefore', async () => {
  const unmarked = await answered(FIXTURE.unmarked);
  expect(unmarked.objects.map((o) => o.id)).toEqual(['visible-row-id-1', 'visible-row-id-2']);
  expect(unmarked.objects.map((o) => o.name)).toEqual(['visible-row-name-1.txt', 'visible-row-name-2.txt']);
  expect(unmarked.numItems).toBe(7300);
  expect(unmarked.hasMoreItems).toBe(true);
  const markedFalse = await answered(FIXTURE.markedFalse);
  expect(markedFalse.objects).toHaveLength(2);
  expect(markedFalse.numItems).toBe(2);
  const nested = await answered(FIXTURE.markNestedUnderAnotherElement);
  expect(nested.objects).toHaveLength(1);
});

it('theMarkIsReadOnlyAsTheStringTrueAtTheTopLevel', () => {
  expect(isCutShortByAclScanLimit({ [CUT_SHORT_MARK]: 'true' })).toBe(true);
  expect(isCutShortByAclScanLimit({ [CUT_SHORT_MARK]: ['false', 'true'] })).toBe(true);
  expect(isCutShortByAclScanLimit({ [CUT_SHORT_MARK]: 'false' })).toBe(false);
  expect(isCutShortByAclScanLimit({ [CUT_SHORT_MARK]: true })).toBe(false);
  expect(isCutShortByAclScanLimit({ other: { [CUT_SHORT_MARK]: 'true' } })).toBe(false);
  expect(isCutShortByAclScanLimit({ somethingElse: 'true' })).toBe(false);
  expect(isCutShortByAclScanLimit(null)).toBe(false);
  expect(isCutShortByAclScanLimit([{ [CUT_SHORT_MARK]: 'true' }])).toBe(false);
});
