import { it, expect, vi, afterEach, afterAll } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, act } from '@testing-library/react';
import { turnAntdMotionOff, turnAntdMotionBackOn, destroyAntdNotices } from '../../test/antdNotices';

/** Waits until {@code text} is on the screen; a timeout fails this lock's own expect(), not the query's harness error. */
async function shown(text: string) {
  await waitFor(() => expect(screen.queryByText(text), `${text} is not shown`).not.toBeNull());
}
import i18n from '../../i18n';
import { SearchResults } from './SearchResults';
import { SearchCutShortError } from '../../services/searchCutShort';

// R344, 3.4.2: a search answered cut short by the ACL scan limit is not shown as a result. After a search that showed a
// result, a cut-short one leaves neither that result, a count nor "more results" on the screen, and shows the guidance to
// narrow the search, in Japanese and in English. Real i18next, so the texts are the shipped ones. The test names are single
// words because the negative-control runner reads them as lock names.
const service = vi.hoisted(() => ({
  search: vi.fn(),
  getTypes: vi.fn().mockResolvedValue([]),
  getSecondaryTypes: vi.fn().mockResolvedValue([]),
  getType: vi.fn(),
  getDownloadUrl: vi.fn(),
}));
vi.mock('../../services/cmis', () => ({
  CMISService: vi.fn().mockImplementation(function () {
    return service;
  }),
}));
vi.mock('../../contexts/AuthContext', () => ({
  useAuth: () => ({ handleAuthError: vi.fn() }),
}));
const router = vi.hoisted(() => ({ setParams: vi.fn(), params: new URLSearchParams() }));
vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useSearchParams: () => [router.params, router.setParams],
}));
vi.mock('../SemanticSearch/SemanticSearch', () => ({ SemanticSearch: () => null }));
vi.mock('./ObjectIdSearch', () => ({ ObjectIdSearch: () => null }));
vi.mock('../PurviewGovernance/PurviewGovernanceSearchSummary', () => ({ PurviewGovernanceSearchSummary: () => null }));

const PREVIOUS = {
  objects: [{
    id: 'previous-row-id', name: 'previous-row-name.txt', objectType: 'cmis:document', baseType: 'cmis:document',
    properties: {}, allowableActions: [],
  }],
  numItems: 1,
  hasMoreItems: true,
};

afterEach(async () => {
  cleanup();
  service.search.mockReset();
  router.setParams.mockReset();
  router.params = new URLSearchParams();
  // Last, after the resets: a HarnessBroken raised here has left the stand-ins reset; the notice it reports stays,
  // and later locks of this file report it too.
  await destroyAntdNotices();
});

// antd's static notices must not outlive a lock: see src/test/antdNotices.tsx.
turnAntdMotionOff();
afterAll(() => {
  turnAntdMotionBackOn();
});

async function searchFor(container: HTMLElement, placeholder: string, keyword: string) {
  fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value: keyword } });
  const submit = container.querySelector('.search-submit-button');
  if (!submit) {
    throw new Error('the search button is not there');
  }
  fireEvent.click(submit);
}

for (const [lang, name, placeholder, resultsTitle, more, guidance] of [
  ['ja', 'aCutShortAnswerLeavesNoPreviousResultInJapanese', '検索キーワードを入力', '検索結果 (1件)', 'さらに結果があります',
    '完全な検索結果を確認できないため、条件を絞って再試行してください。'],
  ['en', 'aCutShortAnswerLeavesNoPreviousResultInEnglish', 'Enter search keywords', 'Search Results (1 item)',
    'More results available', 'The complete search results could not be confirmed. Narrow the search conditions and try again.'],
] as const) {
  it(name, async () => {
    await i18n.changeLanguage(lang);
    service.search.mockResolvedValueOnce(PREVIOUS).mockRejectedValueOnce(new SearchCutShortError());
    const { container } = render(<SearchResults repositoryId="bedroom" />);
    await searchFor(container, placeholder, 'first');
    await shown('previous-row-name.txt');
    expect(screen.queryByText(resultsTitle), 'the results title').not.toBeNull();
    expect(screen.queryByText(more), '"more results"').not.toBeNull();

    await searchFor(container, placeholder, 'second');
    await shown(guidance);
    await waitFor(() => expect(service.search).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('previous-row-name.txt')).toBeNull();
    expect(screen.queryByText(resultsTitle)).toBeNull();
    expect(screen.queryByText(more)).toBeNull();
    expect(container.querySelector('.ant-table')).toBeNull();
  });
}

it('aSearchAnsweredAfterACutShortOneShowsItsResultAndNoGuidance', async () => {
  await i18n.changeLanguage('en');
  service.search.mockRejectedValueOnce(new SearchCutShortError()).mockResolvedValueOnce(PREVIOUS);
  const { container } = render(<SearchResults repositoryId="bedroom" />);
  await searchFor(container, 'Enter search keywords', 'first');
  const guidance = 'The complete search results could not be confirmed. Narrow the search conditions and try again.';
  await shown(guidance);
  await searchFor(container, 'Enter search keywords', 'second');
  await shown('previous-row-name.txt');
  expect(screen.queryByText(guidance)).toBeNull();
});

// R349 (R344 batch, 3.4.2): only the last search started writes the page, and a search that fails for another reason
// leaves no previous result either.
const NEWER = {
  objects: [{
    id: 'newer-row-id', name: 'newer-row-name.txt', objectType: 'cmis:document', baseType: 'cmis:document',
    properties: {}, allowableActions: [],
  }],
  numItems: 1,
  hasMoreItems: false,
};
const ENGLISH_GUIDANCE = 'The complete search results could not be confirmed. Narrow the search conditions and try again.';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

/** Submits the form — the search button holds still while a search runs, the form does not. */
function submitFor(container: HTMLElement, keyword: string) {
  fireEvent.change(screen.getByPlaceholderText('Enter search keywords'), { target: { value: keyword } });
  const form = container.querySelector('.search-submit-button')?.closest('form');
  if (!form) {
    throw new Error('the search form is not there');
  }
  fireEvent.submit(form);
}

it('anOlderSearchAnsweredLaterDoesNotOverwriteANewerCutShort', async () => {
  await i18n.changeLanguage('en');
  const older = deferred<typeof PREVIOUS>();
  service.search.mockReturnValueOnce(older.promise).mockRejectedValueOnce(new SearchCutShortError());
  const { container } = render(<SearchResults repositoryId="bedroom" />);
  submitFor(container, 'older');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));
  submitFor(container, 'newer');
  await shown(ENGLISH_GUIDANCE);
  await act(async () => {
    older.resolve(PREVIOUS);
    await older.promise;
  });
  await settle();
  expect(screen.queryByText('previous-row-name.txt'), 'the older row').toBeNull();
  expect(screen.queryByText(ENGLISH_GUIDANCE), 'the guidance of the newer search').not.toBeNull();
});

it('anOlderCutShortArrivingLaterDoesNotOverwriteANewerAnswer', async () => {
  await i18n.changeLanguage('en');
  const older = deferred<typeof PREVIOUS>();
  service.search.mockReturnValueOnce(older.promise).mockResolvedValueOnce(NEWER);
  const { container } = render(<SearchResults repositoryId="bedroom" />);
  submitFor(container, 'older');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));
  submitFor(container, 'newer');
  await shown('newer-row-name.txt');
  await act(async () => {
    older.reject(new SearchCutShortError());
    await older.promise.catch(() => undefined);
  });
  await settle();
  expect(screen.queryByText('newer-row-name.txt'), 'the row of the newer search').not.toBeNull();
  expect(screen.queryByText(ENGLISH_GUIDANCE), 'the guidance of the older search').toBeNull();
});

it('anOlderAnswerArrivingFirstNeitherWritesNorEndsTheNewerSearch', async () => {
  await i18n.changeLanguage('en');
  const older = deferred<typeof PREVIOUS>();
  const newer = deferred<typeof PREVIOUS>();
  service.search.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);
  const { container } = render(<SearchResults repositoryId="bedroom" />);
  submitFor(container, 'older');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));
  submitFor(container, 'newer');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(2));
  await act(async () => {
    older.resolve(PREVIOUS);
    await older.promise;
  });
  await settle();
  expect(screen.queryByText('previous-row-name.txt'), 'the older row').toBeNull();
  expect(container.querySelector('.search-submit-button.ant-btn-loading'), 'the newer search\'s spinner').not.toBeNull();
  await act(async () => {
    newer.resolve(NEWER);
    await newer.promise;
  });
  await shown('newer-row-name.txt');
});

it('aFailedSearchLeavesNoPreviousResult', async () => {
  await i18n.changeLanguage('en');
  service.search.mockResolvedValueOnce(PREVIOUS).mockRejectedValueOnce(new Error('the search failed'));
  const { container } = render(<SearchResults repositoryId="bedroom" />);
  await searchFor(container, 'Enter search keywords', 'first');
  await shown('previous-row-name.txt');
  await searchFor(container, 'Enter search keywords', 'second');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(2));
  await settle();
  expect(screen.queryByText('previous-row-name.txt'), 'the previous row as the failed search\'s').toBeNull();
  expect(screen.queryByText('Search Results (1 item)'), 'the previous count').toBeNull();
  expect(screen.queryByText(ENGLISH_GUIDANCE), 'the guidance after a failure that is not a cut short').toBeNull();
});

it('aSearchAnsweredAfterThePageWasLeftWritesNoAddress', async () => {
  await i18n.changeLanguage('en');
  const running = deferred<typeof PREVIOUS>();
  service.search.mockReturnValueOnce(running.promise);
  const { container, unmount } = render(<SearchResults repositoryId="bedroom" />);
  submitFor(container, 'left');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));
  router.setParams.mockClear();
  unmount();
  await act(async () => {
    running.resolve(PREVIOUS);
    await running.promise;
  });
  await settle();
  expect(router.setParams, 'the address written after the page was left').not.toHaveBeenCalled();
});

it('goingBackToAnEarlierSearchShowsItsRows', async () => {
  await i18n.changeLanguage('en');
  router.params = new URLSearchParams('q=first-statement');
  service.search.mockResolvedValueOnce(PREVIOUS).mockResolvedValueOnce(NEWER).mockResolvedValueOnce(PREVIOUS);
  const { container, rerender } = render(<SearchResults repositoryId="bedroom" />);
  await shown('previous-row-name.txt');

  // A search from the page; its answer writes the address, which then names it and starts nothing.
  await searchFor(container, 'Enter search keywords', 'newer');
  await shown('newer-row-name.txt');
  const written = router.setParams.mock.calls[router.setParams.mock.calls.length - 1][0] as Record<string, string>;
  router.params = new URLSearchParams(written);
  rerender(<SearchResults repositoryId="bedroom" />);
  await settle();
  expect(service.search, 'a search started by the address its own answer wrote').toHaveBeenCalledTimes(2);

  // Back: the address names the first search again, and the page shows it.
  router.params = new URLSearchParams('q=first-statement');
  rerender(<SearchResults repositoryId="bedroom" />);
  await shown('previous-row-name.txt');
  expect(service.search, 'the search the address names again').toHaveBeenCalledTimes(3);
  expect(screen.queryByText('newer-row-name.txt'), 'the rows of the search the address no longer names').toBeNull();
});

it('goingBackToThePageWithoutASearchShowsNone', async () => {
  await i18n.changeLanguage('en');
  router.params = new URLSearchParams('q=first-statement');
  service.search.mockResolvedValueOnce(PREVIOUS);
  const { rerender } = render(<SearchResults repositoryId="bedroom" />);
  await shown('previous-row-name.txt');

  // Back to the page whose address names no search.
  router.params = new URLSearchParams();
  rerender(<SearchResults repositoryId="bedroom" />);
  await waitFor(() => expect(screen.queryByText('previous-row-name.txt'), 'the rows of a search the address no longer names')
    .toBeNull());
  expect(screen.queryByText('Search Results (1 item)'), 'its count').toBeNull();
});

it('aSearchTheAddressAlreadyNamesWritesNoNewAddress', async () => {
  await i18n.changeLanguage('en');
  router.params = new URLSearchParams('q=first-statement');
  service.search.mockResolvedValueOnce(PREVIOUS);
  render(<SearchResults repositoryId="bedroom" />);
  await shown('previous-row-name.txt');
  expect(router.setParams, 'an address written for the search the address already names').not.toHaveBeenCalled();
});

it('goingBackFromASearchStillRunningShowsNone', async () => {
  await i18n.changeLanguage('en');
  router.params = new URLSearchParams('q=held-statement');
  const held = deferred<typeof PREVIOUS>();
  service.search.mockReturnValueOnce(held.promise);
  const { rerender } = render(<SearchResults repositoryId="bedroom" />);
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(1));

  // Back to the page with no search before the search answers.
  router.params = new URLSearchParams();
  rerender(<SearchResults repositoryId="bedroom" />);
  await act(async () => {
    held.resolve(PREVIOUS);
    await held.promise;
  });
  await settle();
  expect(screen.queryByText('previous-row-name.txt'), 'the rows of a search answered after its address was left').toBeNull();
});

it('goingBackFromACutShortSearchTakesTheGuidanceAway', async () => {
  await i18n.changeLanguage('en');
  router.params = new URLSearchParams('q=cut-statement');
  service.search.mockRejectedValueOnce(new SearchCutShortError());
  const { rerender } = render(<SearchResults repositoryId="bedroom" />);
  await shown(ENGLISH_GUIDANCE);

  router.params = new URLSearchParams();
  rerender(<SearchResults repositoryId="bedroom" />);
  await waitFor(() => expect(screen.queryByText(ENGLISH_GUIDANCE), 'the guidance of a search the address no longer names')
    .toBeNull());
});

it('aSearchAnsweredAfterAForwardToItsAddressWritesNoNewAddress', async () => {
  await i18n.changeLanguage('en');
  router.params = new URLSearchParams('q=first-statement');
  service.search.mockResolvedValueOnce(PREVIOUS);
  const { container, rerender } = render(<SearchResults repositoryId="bedroom" />);
  await shown('previous-row-name.txt');

  // A search from the page; before it answers, a forward brings the address to it.
  const running = deferred<typeof NEWER>();
  service.search.mockReturnValueOnce(running.promise);
  await searchFor(container, 'Enter search keywords', 'newer');
  await waitFor(() => expect(service.search).toHaveBeenCalledTimes(2));
  const query = service.search.mock.calls[1][1] as string;
  router.params = new URLSearchParams({ q: query });
  rerender(<SearchResults repositoryId="bedroom" />);
  router.setParams.mockClear();
  await act(async () => {
    running.resolve(NEWER);
    await running.promise;
  });
  await shown('newer-row-name.txt');
  expect(router.setParams, 'an address written for the search the address names now').not.toHaveBeenCalled();
});
