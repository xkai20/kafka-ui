package com.provectus.kafka.ui.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.provectus.kafka.ui.model.TopicMessageDTO;
import com.provectus.kafka.ui.model.TopicMessageEventDTO;
import com.provectus.kafka.ui.serde.api.DeserializeResult;
import com.provectus.kafka.ui.serde.api.Serde;
import com.provectus.kafka.ui.serdes.ConsumerRecordDeserializer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.utils.Bytes;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class MessagesProcessingTest {

  private static final ConsumerRecordDeserializer STRING_DESERIALIZER = createStringDeserializer();

  @RepeatedTest(5)
  void testSortingAsc() {
    var messagesInOrder = List.of(
        consumerRecord(1, 100L, "1999-01-01T00:00:00+00:00"),
        consumerRecord(0, 0L, "2000-01-01T00:00:00+00:00"),
        consumerRecord(1, 200L, "2000-01-05T00:00:00+00:00"),
        consumerRecord(0, 10L, "2000-01-10T00:00:00+00:00"),
        consumerRecord(0, 20L, "2000-01-20T00:00:00+00:00"),
        consumerRecord(1, 300L, "3000-01-01T00:00:00+00:00"),
        consumerRecord(2, 1000L, "4000-01-01T00:00:00+00:00"),
        consumerRecord(2, 1001L, "2000-01-01T00:00:00+00:00"),
        consumerRecord(2, 1003L, "3000-01-01T00:00:00+00:00")
    );

    var shuffled = new ArrayList<>(messagesInOrder);
    Collections.shuffle(shuffled);

    var sortedList = MessagesProcessing.sortForSending(shuffled, true);
    assertThat(sortedList).containsExactlyElementsOf(messagesInOrder);
  }

  @RepeatedTest(5)
  void testSortingDesc() {
    var messagesInOrder = List.of(
        consumerRecord(1, 300L, "3000-01-01T00:00:00+00:00"),
        consumerRecord(2, 1003L, "3000-01-01T00:00:00+00:00"),
        consumerRecord(0, 20L, "2000-01-20T00:00:00+00:00"),
        consumerRecord(0, 10L, "2000-01-10T00:00:00+00:00"),
        consumerRecord(1, 200L, "2000-01-05T00:00:00+00:00"),
        consumerRecord(0, 0L, "2000-01-01T00:00:00+00:00"),
        consumerRecord(2, 1001L, "2000-01-01T00:00:00+00:00"),
        consumerRecord(2, 1000L, "4000-01-01T00:00:00+00:00"),
        consumerRecord(1, 100L, "1999-01-01T00:00:00+00:00")
    );

    var shuffled = new ArrayList<>(messagesInOrder);
    Collections.shuffle(shuffled);

    var sortedList = MessagesProcessing.sortForSending(shuffled, false);
    assertThat(sortedList).containsExactlyElementsOf(messagesInOrder);
  }

  /*
   * Records of the pages preceding the requested one should be consumed, but not sent to the client
   */
  @Test
  void skipRecordsOfPreviousPagesAscending() {
    var records = List.of(
        consumerRecord(0, 0L, "2000-01-01T00:00:00+00:00", "msg_0"),
        consumerRecord(0, 1L, "2000-01-02T00:00:00+00:00", "msg_1"),
        consumerRecord(0, 2L, "2000-01-03T00:00:00+00:00", "msg_2"),
        consumerRecord(0, 3L, "2000-01-04T00:00:00+00:00", "msg_3"),
        consumerRecord(0, 4L, "2000-01-05T00:00:00+00:00", "msg_4")
    );

    //first page
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, m -> true, true, 2, 0), records))
        .containsExactly("msg_0", "msg_1");
    //second page
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, m -> true, true, 2, 2), records))
        .containsExactly("msg_2", "msg_3");
    //last (incomplete) page
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, m -> true, true, 2, 4), records))
        .containsExactly("msg_4");
    //page after the last one
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, m -> true, true, 2, 6), records))
        .isEmpty();
  }

  @Test
  void skipRecordsOfPreviousPagesDescending() {
    var records = List.of(
        consumerRecord(0, 4L, "2000-01-05T00:00:00+00:00", "msg_4"),
        consumerRecord(0, 3L, "2000-01-04T00:00:00+00:00", "msg_3"),
        consumerRecord(0, 2L, "2000-01-03T00:00:00+00:00", "msg_2"),
        consumerRecord(0, 1L, "2000-01-02T00:00:00+00:00", "msg_1"),
        consumerRecord(0, 0L, "2000-01-01T00:00:00+00:00", "msg_0")
    );

    //first page contains the newest messages
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, m -> true, false, 2, 0), records))
        .containsExactly("msg_4", "msg_3");
    //second page contains the next (older) ones
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, m -> true, false, 2, 2), records))
        .containsExactly("msg_2", "msg_1");
    //last (incomplete) page
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, m -> true, false, 2, 4), records))
        .containsExactly("msg_0");
  }

  /*
   * Filter is applied before paging, so pages contain only messages matching the filter
   */
  @Test
  void filterIsAppliedBeforePaging() {
    var records = List.of(
        consumerRecord(0, 0L, "2000-01-01T00:00:00+00:00", "msg_0"),
        consumerRecord(0, 1L, "2000-01-02T00:00:00+00:00", "filtered_out_1"),
        consumerRecord(0, 2L, "2000-01-03T00:00:00+00:00", "msg_2"),
        consumerRecord(0, 3L, "2000-01-04T00:00:00+00:00", "filtered_out_3"),
        consumerRecord(0, 4L, "2000-01-05T00:00:00+00:00", "msg_4")
    );
    Predicate<TopicMessageDTO> filter = msg -> msg.getContent().startsWith("msg");

    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, filter, true, 2, 0), records))
        .containsExactly("msg_0", "msg_2");
    assertThat(sentContents(new MessagesProcessing(STRING_DESERIALIZER, filter, true, 2, 2), records))
        .containsExactly("msg_4");
  }

  private List<String> sentContents(MessagesProcessing messagesProcessing,
                                    Iterable<ConsumerRecord<Bytes, Bytes>> records) {
    List<String> sent = new ArrayList<>();
    Flux.<TopicMessageEventDTO>create(sink -> messagesProcessing.send(sink, records))
        .filter(evt -> evt.getType() == TopicMessageEventDTO.TypeEnum.MESSAGE)
        .map(evt -> evt.getMessage().getContent())
        .subscribe(sent::add);
    return sent;
  }

  private static ConsumerRecordDeserializer createStringDeserializer() {
    Serde.Deserializer deserializer = (headers, data) ->
        new DeserializeResult(new String(data), DeserializeResult.Type.STRING, Map.of());
    return new ConsumerRecordDeserializer(
        "String", deserializer,
        "String", deserializer,
        "String", deserializer, deserializer,
        msg -> msg
    );
  }

  private ConsumerRecord<Bytes, Bytes> consumerRecord(int partition, long offset, String ts) {
    return new ConsumerRecord<>(
        "topic", partition, offset, OffsetDateTime.parse(ts).toInstant().toEpochMilli(),
        TimestampType.CREATE_TIME,
        0, 0, null, null, new RecordHeaders(), Optional.empty()
    );
  }

  private ConsumerRecord<Bytes, Bytes> consumerRecord(int partition, long offset, String ts, String value) {
    return new ConsumerRecord<>(
        "topic", partition, offset, OffsetDateTime.parse(ts).toInstant().toEpochMilli(),
        TimestampType.CREATE_TIME,
        0, 0, null, Bytes.wrap(value.getBytes()), new RecordHeaders(), Optional.empty()
    );
  }

}
