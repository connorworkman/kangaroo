package com.conductor.kafka.hadoop;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import kafka.api.FetchRequest;
import kafka.common.ErrorMapping;
import kafka.consumer.SimpleConsumer;
import kafka.message.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import scala.collection.Iterator;

import com.conductor.kafka.Broker;
import com.conductor.kafka.Partition;
import com.conductor.zk.ZkUtils;

/**
 * @author cgreen
 */
@RunWith(MockitoJUnitRunner.class)
public class KafkaRecordReaderTest {

    @Mock
    private TaskAttemptContext context;
    @Mock
    private SimpleConsumer mockConsumer;
    @Mock
    private ByteBufferMessageSet mockMessage;
    @Mock
    private Iterator<MessageAndOffset> mockIterator;

    private Configuration conf;
    private KafkaInputSplit split;
    private KafkaRecordReader reader;
    private Partition partition;

    @Before
    public void setUp() throws Exception {
        conf = new Configuration(false);
        when(context.getConfiguration()).thenReturn(conf);
        final Job job = mock(Job.class);
        when(job.getConfiguration()).thenReturn(conf);

        KafkaInputFormat.setConsumerGroup(job, "group");
        KafkaInputFormat.setSocketTimeout(job, 1000);
        KafkaInputFormat.setBufferSize(job, 4096);
        KafkaInputFormat.setFetchSize(job, 2048);

        final Broker broker = new Broker("localhost", 9092, 1);
        this.partition = new Partition("topic", 0, broker);
        split = new KafkaInputSplit(partition, 0, 100, true);

        reader = spy(new KafkaRecordReader());
        reader.initialize(split, context);
    }

    @Test
    public void testInitialize() throws Exception {
        assertEquals(conf, reader.getConf());
        assertEquals(split, reader.getSplit());
        assertEquals("localhost", reader.getConsumer(reader.getSplit(), reader.getConf()).host());
        assertEquals(9092, reader.getConsumer(reader.getSplit(), reader.getConf()).port());
        assertEquals(1000, reader.getConsumer(reader.getSplit(), reader.getConf()).soTimeout());
        assertEquals(4096, reader.getConsumer(reader.getSplit(), reader.getConf()).bufferSize());
        assertEquals(2048, reader.getFetchSize());
        assertEquals(0, reader.getStart());
        assertEquals(100, reader.getEnd());
        assertEquals(0, reader.getPos());
        assertEquals(0, reader.getCurrentOffset());
        assertNull("Iterator should not have been initialized yet!", reader.getCurrentMessageItr());
        assertNull("Current key should be null!", reader.getCurrentKey());
        assertNull("Current value should be null!", reader.getCurrentValue());
    }

    @Test
    public void testNextKeyValue() throws Exception {
        doReturn(true).when(reader).continueItr();
        doReturn(mockIterator).when(reader).getCurrentMessageItr();
        final byte[] messageContent = { 1 };
        final MessageAndOffset msg = new MessageAndOffset(new Message(messageContent), 100l);
        when(mockIterator.next()).thenReturn(msg);

        assertTrue(reader.nextKeyValue());
        assertEquals(100l, reader.getPos());
        assertEquals(100l, reader.getCurrentKey().get());
        assertArrayEquals(messageContent, reader.getCurrentValue().getBytes());
    }

    @Test(expected = Exception.class)
    public void testContinueItrException() throws Exception {
        doReturn(mockConsumer).when(reader).getConsumer(split, conf);
        reader.initialize(split, context);
        when(mockConsumer.fetch(any(FetchRequest.class))).thenReturn(mockMessage);
        when(mockMessage.getErrorCode()).thenReturn(ErrorMapping.InvalidFetchSizeCode());
        reader.continueItr();
        fail();
    }

    @Test
    public void testContinueItrOffsetOutOfRange() throws Exception {
        doReturn(mockConsumer).when(reader).getConsumer(split, conf);
        reader.initialize(split, context);
        when(mockConsumer.fetch(any(FetchRequest.class))).thenReturn(mockMessage);
        when(mockMessage.getErrorCode()).thenReturn(ErrorMapping.OffsetOutOfRangeCode());
        assertFalse("Should be done with split!", reader.continueItr());
    }

    @Test
    public void testContinueItr() throws Exception {
        doReturn(mockConsumer).when(reader).getConsumer(split, conf);
        // unfortunately, FetchRequest does not implement equals, so we have to do any(), and validate with answer
        when(mockConsumer.fetch(any(FetchRequest.class))).thenAnswer(new Answer<ByteBufferMessageSet>() {
            @Override
            public ByteBufferMessageSet answer(final InvocationOnMock invocation) throws Throwable {
                final FetchRequest request = (FetchRequest) invocation.getArguments()[0];
                assertEquals("topic", request.topic());
                assertEquals(0, request.partition());
                assertEquals(0, request.offset());
                assertEquals(100, request.maxSize());
                return mockMessage;
            }
        });
        when(mockMessage.getErrorCode()).thenReturn(ErrorMapping.NoError());
        when(mockMessage.iterator()).thenReturn(mockIterator);
        when(mockMessage.validBytes()).thenReturn(100l);
        when(mockIterator.hasNext()).thenReturn(true);
        reader.initialize(split, context);

        assertTrue("Should be able to continue iterator!", reader.continueItr());
        assertEquals(mockIterator, reader.getCurrentMessageItr());
        assertEquals(100, reader.getCurrentOffset());

        when(mockIterator.hasNext()).thenReturn(false);
        assertFalse("Should be done with split!", reader.continueItr());
        // call it again just for giggles
        assertFalse("Should be done with split!", reader.continueItr());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testContinueItrMultipleIterations() throws Exception {
        // init split
        doReturn(mockConsumer).when(reader).getConsumer(split, conf);
        split.setEndOffset(4097);
        reader.initialize(split, context);

        // first iteration
        final Iterator<MessageAndOffset> mockIterator1 = mock(Iterator.class);
        when(mockConsumer.fetch(any(FetchRequest.class))).thenReturn(mockMessage);
        when(mockMessage.getErrorCode()).thenReturn(ErrorMapping.NoError());
        when(mockMessage.iterator()).thenReturn(mockIterator1);
        when(mockMessage.validBytes()).thenReturn(2048l);
        when(mockIterator1.hasNext()).thenReturn(true);

        assertTrue("Should be able to continue iterator!", reader.continueItr());

        // reset iterator for second iteration
        when(mockIterator1.hasNext()).thenReturn(false);
        final Iterator<MessageAndOffset> mockIterator2 = mock(Iterator.class);
        when(mockMessage.iterator()).thenReturn(mockIterator2);
        when(mockIterator2.hasNext()).thenReturn(true);

        assertTrue("Should be able to continue iterator!", reader.continueItr());

        // reset iterator for third iteration
        when(mockIterator2.hasNext()).thenReturn(false);
        final Iterator<MessageAndOffset> mockIterator3 = mock(Iterator.class);
        when(mockMessage.iterator()).thenReturn(mockIterator3);
        when(mockIterator3.hasNext()).thenReturn(true);
        when(mockMessage.validBytes()).thenReturn(1l);

        assertTrue("Should be able to continue iterator!", reader.continueItr());

        // out of bytes to read
        when(mockIterator3.hasNext()).thenReturn(false);
        assertFalse("Should be done with split!", reader.continueItr());
    }

    @Test
    public void testGetProgress() throws Exception {
        assertEquals(0f, reader.getProgress(), 0f);

        doReturn(true).when(reader).continueItr();
        doReturn(mockIterator).when(reader).getCurrentMessageItr();
        final byte[] messageContent = { 1 };
        MessageAndOffset msg = new MessageAndOffset(new Message(messageContent), 10l);
        when(mockIterator.next()).thenReturn(msg);

        assertTrue(reader.nextKeyValue());
        assertEquals(.1f, reader.getProgress(), 0f);

        msg = new MessageAndOffset(new Message(messageContent), 99l);
        when(mockIterator.next()).thenReturn(msg);

        assertTrue(reader.nextKeyValue());
        assertTrue(reader.getProgress() < 1);

        msg = new MessageAndOffset(new Message(messageContent), 100l);
        when(mockIterator.next()).thenReturn(msg);
        assertTrue(reader.nextKeyValue());
        assertEquals(1f, reader.getProgress(), 0f);
    }

    @Test
    public void testClose() throws Exception {
        doReturn(mockConsumer).when(reader).getConsumer(split, conf);
        split.setPartitionCommitter(false);
        reader.initialize(split, context);
        doNothing().when(reader).commitOffset();

        reader.close();
        verify(reader, never()).commitOffset();
        verify(mockConsumer, times(1)).close();

        split.setPartitionCommitter(true);
        reader.initialize(split, context);
        reader.close();
        verify(reader, times(1)).commitOffset();
    }

    @Test
    public void testCanCallNext() throws Exception {
        doReturn(null).when(reader).getCurrentMessageItr();
        assertFalse("Iterator is null, should not be able to call next on it!", reader.canCallNext());

        doReturn(mockIterator).when(reader).getCurrentMessageItr();
        when(mockIterator.hasNext()).thenReturn(false);
        assertFalse("Iterator does not have a next item, should not be able to call next()!", reader.canCallNext());

        when(mockIterator.hasNext()).thenReturn(true);
        assertTrue("Iterator has elements, should be able to call next().", reader.canCallNext());
    }

    @Test
    public void testCommitOffset() throws Exception {
        final ZkUtils mockZk = mock(ZkUtils.class);
        doReturn(mockZk).when(reader).getZk();
        reader.commitOffset();
        verify(mockZk).setLastCommit("group", partition, 0l, true);
    }
}
