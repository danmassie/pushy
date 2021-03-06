/* Copyright (c) 2013 RelayRides
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.relayrides.pushy.apns;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.ArrayList;
import java.util.List;

public class MockFeedbackServer {
	
	private final int port;
	
	private final ArrayList<ExpiredToken> expiredTokens;
	
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	
	private volatile boolean closeWhenDone = false;
	
	private class ExpiredTokenEncoder extends MessageToByteEncoder<ExpiredToken> {

		@Override
		protected void encode(final ChannelHandlerContext context, final ExpiredToken expiredToken, final ByteBuf out) {
			out.writeInt((int) (expiredToken.getExpiration().getTime() / 1000L));
			out.writeShort(expiredToken.getToken().length);
			out.writeBytes(expiredToken.getToken());
		}
	}
	
	private class MockFeedbackServerHandler extends ChannelInboundHandlerAdapter {
		
		private final MockFeedbackServer feedbackServer;
		
		public MockFeedbackServerHandler(final MockFeedbackServer feedbackServer) {
			this.feedbackServer = feedbackServer;
		}
		
		@Override
	    public void channelActive(final ChannelHandlerContext context) {
			
			final List<ExpiredToken> expiredTokens = this.feedbackServer.getAndClearAllExpiredTokens();
			
			ChannelFuture lastWriteFuture = null;
			
			for (final ExpiredToken expiredToken : expiredTokens) {
				lastWriteFuture = context.write(expiredToken);
			}
			
			context.flush();

			if (this.feedbackServer.closeWhenDone && lastWriteFuture != null) {
				lastWriteFuture.addListener(ChannelFutureListener.CLOSE);
			}
		}
	}
	
	public MockFeedbackServer(final int port) {
		this.port = port;
		
		this.expiredTokens = new ArrayList<ExpiredToken>();
	}
	
	public void start() throws InterruptedException {
		this.bossGroup = new NioEventLoopGroup();
		this.workerGroup = new NioEventLoopGroup();
		
		final ServerBootstrap bootstrap = new ServerBootstrap();
		
		bootstrap.group(this.bossGroup, this.workerGroup);
		bootstrap.channel(NioServerSocketChannel.class);
		
		final MockFeedbackServer server = this;
		bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {

			@Override
			protected void initChannel(final SocketChannel channel) throws Exception {
				channel.pipeline().addLast("encoder", new ExpiredTokenEncoder());
				channel.pipeline().addLast("handler", new MockFeedbackServerHandler(server));
			}
		});
		
		bootstrap.bind(this.port).sync();
	}
	
	public void shutdown() throws InterruptedException {
		this.workerGroup.shutdownGracefully();
		this.bossGroup.shutdownGracefully();
	}
	
	public synchronized void addExpiredToken(final ExpiredToken expiredToken) {
		this.expiredTokens.add(expiredToken);
	}
	
	protected synchronized List<ExpiredToken> getAndClearAllExpiredTokens() {
		final ArrayList<ExpiredToken> tokensToReturn = new ArrayList<ExpiredToken>(this.expiredTokens);
		this.expiredTokens.clear();
		
		return tokensToReturn;
	}
	
	public void setCloseWhenDone(final boolean closeWhenDone) {
		this.closeWhenDone = closeWhenDone;
	}
}
