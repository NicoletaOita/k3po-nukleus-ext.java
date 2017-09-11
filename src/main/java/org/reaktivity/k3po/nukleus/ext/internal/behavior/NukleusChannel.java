/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.k3po.nukleus.ext.internal.behavior;

import static org.jboss.netty.buffer.ChannelBuffers.dynamicBuffer;
import static org.reaktivity.k3po.nukleus.ext.internal.behavior.NukleusThrottleMode.MESSAGE;

import java.util.Deque;
import java.util.LinkedList;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelSink;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.kaazing.k3po.driver.internal.netty.bootstrap.channel.AbstractChannel;
import org.kaazing.k3po.driver.internal.netty.channel.ChannelAddress;

public abstract class NukleusChannel extends AbstractChannel<NukleusChannelConfig>
{
    private int sourceWindowBudget;
    private int sourceWindowPadding;
    private int targetWindowBudget;
    private int targetWindowPadding;

    private int targetWrittenBytes;
    private int targetAcknowledgedBytes;

    private long sourceId;
    private long targetId;

    private int targetAcknowlegedBytesCheckpoint = -1;

    final NukleusReaktor reaktor;
    final Deque<MessageEvent> writeRequests;

    private NukleusExtensionKind readExtKind;
    private ChannelBuffer readExtBuffer;

    private NukleusExtensionKind writeExtKind;
    private ChannelBuffer writeExtBuffer;

    private boolean targetWriteRequestInProgress;

    private ChannelFuture beginOutputFuture;
    private ChannelFuture beginInputFuture;

    NukleusChannel(
        NukleusServerChannel parent,
        ChannelFactory factory,
        ChannelPipeline pipeline,
        ChannelSink sink,
        NukleusReaktor reaktor)
    {
        super(parent, factory, pipeline, sink, new DefaultNukleusChannelConfig());

        this.reaktor = reaktor;
        this.writeRequests = new LinkedList<>();
        this.readExtBuffer = dynamicBuffer(8192);
        this.writeExtBuffer = dynamicBuffer(8192);
        this.targetId = getId();
    }

    @Override
    public NukleusChannelAddress getLocalAddress()
    {
        return (NukleusChannelAddress) super.getLocalAddress();
    }

    @Override
    public NukleusChannelAddress getRemoteAddress()
    {
        return (NukleusChannelAddress) super.getRemoteAddress();
    }

    @Override
    protected void setBound()
    {
        super.setBound();
    }

    @Override
    protected void setConnected()
    {
        super.setConnected();
    }

    @Override
    protected boolean isReadClosed()
    {
        return super.isReadClosed();
    }

    @Override
    protected boolean isWriteClosed()
    {
        return super.isWriteClosed();
    }

    @Override
    protected boolean setReadClosed()
    {
        return super.setReadClosed();
    }

    @Override
    protected boolean setWriteClosed()
    {
        return super.setWriteClosed();
    }

    @Override
    protected boolean setReadAborted()
    {
        return super.setReadAborted();
    }

    @Override
    protected boolean setWriteAborted()
    {
        return super.setWriteAborted();
    }

    @Override
    protected boolean setClosed()
    {
        return super.setClosed();
    }

    @Override
    protected void setRemoteAddress(ChannelAddress remoteAddress)
    {
        super.setRemoteAddress(remoteAddress);
    }

    @Override
    protected void setLocalAddress(ChannelAddress localAddress)
    {
        super.setLocalAddress(localAddress);
    }

    @Override
    public String toString()
    {
        ChannelAddress localAddress = this.getLocalAddress();
        String description = localAddress != null ? localAddress.toString() : super.toString();
        return String.format("%s [sourceId=%d, targetId=%d]", description, sourceId, targetId);
    }

    public void sourceWindow(
        int credit,
        int padding)
    {
        sourceWindowBudget += credit;
        sourceWindowPadding += padding;
        assert sourceWindowPadding >= 0 && sourceWindowBudget >= 0;
    }

    public int sourceWindow()
    {
        return Math.max(sourceWindowBudget - sourceWindowPadding, 0);
    }

    public void sourceId(
        long sourceId)
    {
        this.sourceId = sourceId;
    }

    public long sourceId()
    {
        return sourceId;
    }

    public long targetId()
    {
        return targetId;
    }

    public ChannelFuture beginOutputFuture()
    {
        if (beginOutputFuture == null)
        {
            beginOutputFuture = Channels.future(this);
        }

        return beginOutputFuture;
    }

    public ChannelFuture beginInputFuture()
    {
        if (beginInputFuture == null)
        {
            beginInputFuture = Channels.future(this);
        }

        return beginInputFuture;
    }

    public int targetWindow()
    {
        return Math.max(targetWindowBudget - targetWindowPadding, 0);
    }

    public boolean targetWritable()
    {
        return targetWindowBudget > targetWindowPadding || !getConfig().hasThrottle();
    }

    public int targetWriteableBytes(
        int writableBytes)
    {
        return getConfig().hasThrottle() ? Math.min(targetWindow(), writableBytes) : writableBytes;
    }

    public void targetWritten(
        int writtenBytes)
    {
        targetWrittenBytes += writtenBytes;
        targetWindowBudget -= writtenBytes + targetWindowPadding;
        assert targetWindowPadding >= 0 && targetWindowBudget >= 0;
    }

    public void targetWindowUpdate(
        int credit,
        int padding)
    {
        targetWindowBudget += credit;
        targetWindowPadding += padding;

        // approximation for window acknowledgment
        // does not account for any change to total available window after initial window
        if (targetWrittenBytes > 0)
        {
            targetAcknowledgedBytes += credit;
        }

        if (getConfig().getThrottle() == MESSAGE && targetWriteRequestInProgress)
        {
            if (targetAcknowledgedBytes >= targetAcknowlegedBytesCheckpoint)
            {
                completeWriteRequestIfFullyWritten();
            }
        }
    }

    public void targetWriteRequestProgressing()
    {
        if (getConfig().getThrottle() == MESSAGE)
        {
            final MessageEvent writeRequest = writeRequests.peekFirst();
            final ChannelBuffer message = (ChannelBuffer) writeRequest.getMessage();
            targetAcknowlegedBytesCheckpoint = targetWrittenBytes + message.readableBytes();
            targetWriteRequestInProgress = true;
        }
    }

    public ChannelBuffer writeExtBuffer(
        NukleusExtensionKind writeExtKind,
        boolean readonly)
    {
        if (this.writeExtKind != writeExtKind)
        {
            if (readonly)
            {
                return ChannelBuffers.EMPTY_BUFFER;
            }
            else
            {
                writeExtBuffer.clear();
                this.writeExtKind = writeExtKind;
            }
        }

        return writeExtBuffer;
    }

    public ChannelBuffer readExtBuffer(
        NukleusExtensionKind readExtKind)
    {
        if (this.readExtKind != readExtKind)
        {
            readExtBuffer.clear();
            this.readExtKind = readExtKind;
        }

        return readExtBuffer;
    }

    public void targetWriteRequestProgress()
    {
        switch (getConfig().getThrottle())
        {
        case MESSAGE:
            if (targetWriteRequestInProgress && targetAcknowledgedBytes >= targetAcknowlegedBytesCheckpoint)
            {
                completeWriteRequestIfFullyWritten();
            }
            break;
        default:
            completeWriteRequestIfFullyWritten();
            break;
        }
    }

    public boolean isTargetWriteRequestInProgress()
    {
        return targetWriteRequestInProgress;
    }

    private void completeWriteRequestIfFullyWritten()
    {
        final MessageEvent writeRequest = writeRequests.peekFirst();
        final ChannelBuffer message = (ChannelBuffer) writeRequest.getMessage();
        if (!message.readable())
        {
            targetWriteRequestInProgress = false;
            writeRequests.removeFirst();
            writeRequest.getFuture().setSuccess();
        }
    }
}
