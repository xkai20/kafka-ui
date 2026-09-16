import React from 'react';
import { act, screen, waitFor } from '@testing-library/react';
import { render, EventSourceMock, WithRoute } from 'lib/testHelpers';
import Messages, {
  SeekDirectionOptions,
  SeekDirectionOptionsObj,
} from 'components/Topics/Topic/Messages/Messages';
import { SeekDirection, SeekType, TopicMessage } from 'generated-sources';
import userEvent from '@testing-library/user-event';
import { clusterTopicMessagesPath } from 'lib/paths';
import { useSerdes } from 'lib/hooks/api/topicMessages';
import { serdesPayload } from 'lib/fixtures/topicMessages';
import { useTopicDetails } from 'lib/hooks/api/topics';
import { externalTopicPayload } from 'lib/fixtures/topics';
import {
  topicMessagePayload,
  topicMessagesMetaPayload,
} from 'redux/reducers/topicMessages/__test__/fixtures';

jest.mock('lib/hooks/api/topicMessages', () => ({
  useSerdes: jest.fn(),
}));

jest.mock('lib/hooks/api/topics', () => ({
  useTopicDetails: jest.fn(),
}));

describe('Messages', () => {
  const searchParams = `?filterQueryType=STRING_CONTAINS&attempt=0&limit=100&seekDirection=${SeekDirection.FORWARD}&seekType=${SeekType.OFFSET}&seekTo=0::9`;
  const renderComponent = (param: string = searchParams) => {
    const query = new URLSearchParams(param).toString();
    const path = `${clusterTopicMessagesPath()}?${query}`;
    return render(
      <WithRoute path={clusterTopicMessagesPath()}>
        <Messages />
      </WithRoute>,
      {
        initialEntries: [path],
      }
    );
  };

  beforeEach(() => {
    Object.defineProperty(window, 'EventSource', {
      value: EventSourceMock,
      configurable: true,
      writable: true,
    });
    (useSerdes as jest.Mock).mockImplementation(() => ({
      data: serdesPayload,
    }));
    (useTopicDetails as jest.Mock).mockImplementation(() => ({
      data: externalTopicPayload,
    }));
  });
  describe('component rendering default behavior with the search params', () => {
    beforeEach(() => {
      renderComponent();
    });
    it('should check default seekDirection if it actually take the value from the url', () => {
      expect(screen.getAllByRole('listbox')[3]).toHaveTextContent(
        SeekDirectionOptionsObj[SeekDirection.FORWARD].label
      );
    });

    it('should check the SeekDirection select changes with live option', async () => {
      const seekDirectionSelect = screen.getAllByRole('listbox')[3];
      const seekDirectionOption = screen.getAllByRole('option')[3];

      expect(seekDirectionOption).toHaveTextContent(
        SeekDirectionOptionsObj[SeekDirection.FORWARD].label
      );

      const labelValue1 = SeekDirectionOptions[1].label;
      await userEvent.click(seekDirectionSelect);
      await userEvent.selectOptions(seekDirectionSelect, [labelValue1]);
      expect(seekDirectionOption).toHaveTextContent(labelValue1);

      const labelValue0 = SeekDirectionOptions[0].label;
      await userEvent.click(seekDirectionSelect);
      await userEvent.selectOptions(seekDirectionSelect, [labelValue0]);
      expect(seekDirectionOption).toHaveTextContent(labelValue0);

      const liveOptionConf = SeekDirectionOptions[2];
      const labelValue2 = liveOptionConf.label;
      await userEvent.click(seekDirectionSelect);

      const options = screen.getAllByRole('option');
      const liveModeLi = options.find(
        (option) => option.getAttribute('value') === liveOptionConf.value
      );
      expect(liveModeLi).toBeInTheDocument();
      if (!liveModeLi) return; // to make TS happy
      await userEvent.selectOptions(seekDirectionSelect, [liveModeLi]);
      expect(seekDirectionOption).toHaveTextContent(labelValue2);

      await waitFor(() => {
        expect(screen.getByRole('contentLoader')).toBeInTheDocument();
      });
    });
  });

  describe('Component rendering with custom Url search params', () => {
    it('reacts to a change of seekDirection in the url which make the select pick up different value', () => {
      renderComponent(
        searchParams.replace(SeekDirection.FORWARD, SeekDirection.BACKWARD)
      );
      expect(screen.getAllByRole('listbox')[3]).toHaveTextContent(
        SeekDirectionOptionsObj[SeekDirection.BACKWARD].label
      );
    });
  });

  describe('Component rendering without any search params', () => {
    it('should load the first page of the newest messages', async () => {
      const requestedUrls: string[] = [];
      class EventSourceSpy extends EventSourceMock {
        constructor(url: string) {
          super(url);
          requestedUrls.push(url);
        }
      }
      Object.defineProperty(window, 'EventSource', {
        value: EventSourceSpy,
      });

      renderComponent('');

      await waitFor(() => {
        expect(requestedUrls[requestedUrls.length - 1]).toContain(
          `seekDirection=${SeekDirection.BACKWARD}`
        );
      });
      const lastRequestedUrl = requestedUrls[requestedUrls.length - 1];
      expect(lastRequestedUrl).toContain(
        `seekDirection=${SeekDirection.BACKWARD}`
      );
      expect(lastRequestedUrl).toContain(`seekType=${SeekType.LATEST}`);
      expect(lastRequestedUrl).toContain('page=0');
      expect(lastRequestedUrl).toContain('limit=30');
    });
  });

  describe('Pagination', () => {
    const paginationParams =
      'filterQueryType=STRING_CONTAINS&attempt=1&limit=30&page=0&seekDirection=BACKWARD&seekType=LATEST&keySerde=String&valueSerde=String';

    const createEventSourceSpy = (
      requestedUrls: string[],
      createdSources: (EventSourceMock & { onerror?: () => void })[]
    ) => {
      class EventSourceSpy extends EventSourceMock {
        constructor(url: string) {
          super(url);
          requestedUrls.push(url);
          createdSources.push(this);
        }
      }
      Object.defineProperty(window, 'EventSource', {
        value: EventSourceSpy,
        configurable: true,
        writable: true,
      });
    };

    it('should request the messages page which is set in the url', async () => {
      const requestedUrls: string[] = [];
      const createdSources: (EventSourceMock & { onerror?: () => void })[] = [];
      createEventSourceSpy(requestedUrls, createdSources);

      renderComponent(paginationParams.replace('page=0', 'page=2'));

      await waitFor(() => {
        expect(requestedUrls[requestedUrls.length - 1]).toContain('page=2');
      });
    });

    it('should request the next messages page when the Next button is clicked', async () => {
      const requestedUrls: string[] = [];
      const createdSources: (EventSourceMock & { onerror?: () => void })[] = [];
      createEventSourceSpy(requestedUrls, createdSources);

      const messages: TopicMessage[] = Array.from({ length: 30 }, (_, index) => ({
        ...topicMessagePayload,
        offset: index,
      }));
      const path = `${clusterTopicMessagesPath()}?${new URLSearchParams(
        paginationParams
      ).toString()}`;
      render(
        <WithRoute path={clusterTopicMessagesPath()}>
          <Messages />
        </WithRoute>,
        {
          initialEntries: [path],
          preloadedState: {
            topicMessages: {
              messages,
              meta: { ...topicMessagesMetaPayload },
              isFetching: false,
              messageEventType: '',
            },
          },
        }
      );

      await waitFor(() => expect(createdSources.length).toBeGreaterThan(0));
      //finish messages loading to make pagination controls clickable
      act(() => createdSources[createdSources.length - 1].onerror?.());

      await userEvent.click(screen.getByText(/next/i));

      await waitFor(() => {
        expect(requestedUrls[requestedUrls.length - 1]).toContain('page=1');
      });
    });
  });
});
