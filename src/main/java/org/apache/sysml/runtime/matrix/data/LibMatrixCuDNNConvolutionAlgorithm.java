/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.matrix.data;

import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.instructions.gpu.GPUInstruction;
import org.apache.sysml.runtime.instructions.gpu.context.GPUContext;
import org.apache.sysml.utils.GPUStatistics;

import jcuda.Pointer;
import jcuda.jcudnn.cudnnConvolutionBwdDataPreference;
import jcuda.jcudnn.cudnnConvolutionBwdFilterPreference;
import jcuda.jcudnn.cudnnConvolutionDescriptor;
import jcuda.jcudnn.cudnnConvolutionFwdPreference;
import jcuda.jcudnn.cudnnFilterDescriptor;
import jcuda.jcudnn.cudnnTensorDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnCreateConvolutionDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnCreateFilterDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnCreateTensorDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnDestroyConvolutionDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnDestroyFilterDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnDestroyTensorDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnSetConvolution2dDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnSetFilter4dDescriptor;
import static jcuda.jcudnn.JCudnn.cudnnSetTensor4dDescriptor;
import static jcuda.jcudnn.cudnnConvolutionMode.CUDNN_CROSS_CORRELATION;
import static jcuda.jcudnn.cudnnDataType.CUDNN_DATA_DOUBLE;
import static jcuda.jcudnn.cudnnTensorFormat.CUDNN_TENSOR_NCHW;

/**
 * This class is a wrapper that contain necessary data structures to invoke 
 * a cudnn convolution* functions (such as cudnnConvolutionForward, etc)
 * 
 * It implements autocloseable to simplify the LibMatrixCuDNN code and also avoids potential memory leaks.
 * 
 * The caller has to use the factory methods: cudnnGetConvolutionForwardAlgorithm, 
 * cudnnGetConvolutionBackwardFilterAlgorithm and cudnnGetConvolutionBackwardDataAlgorithm
 * to get the LibMatrixCuDNNConvolutionAlgorithm object.
 * The naming of this methods is consistent with that of CuDNN library.
 *  
 */
public class LibMatrixCuDNNConvolutionAlgorithm implements java.lang.AutoCloseable {
	// Limit the workspace available to cudnn convolution operation to 1 GB
	private static long MAX_WORKSPACE_LIMIT_BYTES = (long) 1e+9;
	
	public int algo = -1;
	public Pointer workSpace = new Pointer();
	public long sizeInBytes = 0;
	cudnnTensorDescriptor nchwTensorDesc = null;
	cudnnTensorDescriptor nkpqTensorDesc = null;
	cudnnFilterDescriptor filterDesc = null;
	cudnnConvolutionDescriptor convDesc = null;
	GPUContext gCtx = null; String instName = null;
	
	private LibMatrixCuDNNConvolutionAlgorithm(GPUContext gCtx, String instName, int N, int C, int H, int W, int K, int R, int S, 
			int pad_h, int pad_w, int stride_h, int stride_w, int P, int Q) throws DMLRuntimeException {
		int padding[] = {pad_h, pad_w};
		int strides[] = {stride_h, stride_w};
		convDesc = allocateConvolutionDescriptor(padding, strides);
		this.gCtx = gCtx;
		this.instName = instName;
		nchwTensorDesc = allocateTensorDescriptor(N, C, H, W);
		nkpqTensorDesc = allocateTensorDescriptor(N, K, P, Q);
		filterDesc = allocateFilterDescriptor(K, C, R, S);
	}
	
	/**
	 * Deallocates the tensor and filter descriptors as well as allocated workspace
	 */
	@Override
	public void close() {
		long t3 = 0;
		if (GPUStatistics.DISPLAY_STATISTICS) t3 = System.nanoTime();
		if(nchwTensorDesc != null)
			cudnnDestroyTensorDescriptor(nchwTensorDesc);
		if(nkpqTensorDesc != null)
			cudnnDestroyTensorDescriptor(nkpqTensorDesc);
		if(filterDesc != null)
			cudnnDestroyFilterDescriptor(filterDesc);
		if(convDesc != null)
			cudnnDestroyConvolutionDescriptor(convDesc);
		if(sizeInBytes != 0)
			gCtx.cudaFreeHelper(instName, workSpace);
		if(GPUStatistics.DISPLAY_STATISTICS)
			GPUStatistics.maintainCPMiscTimes(instName, GPUInstruction.MISC_TIMER_CUDNN_CLEANUP, System.nanoTime() - t3);
	}
	
	/**
	 * Factory method to get the algorithm wrapper for convolution forward
	 * 
	 * @param gCtx     a valid {@link GPUContext}
	 * @param instName the invoking instruction's name for record {@link org.apache.sysml.utils.Statistics}.
	 * @param N        number of input images
	 * @param C        number of channels
	 * @param H        height of each image
	 * @param W        width of each image
	 * @param K        number of output "channels"
	 * @param R        height of filter
	 * @param S        width of filter
	 * @param pad_h    padding height
	 * @param pad_w    padding width
	 * @param stride_h stride height
	 * @param stride_w string width
	 * @param P        output height
	 * @param Q        output width
	 * @param workspaceLimit maximum intermediate memory to use
	 * @return algorithm wrapper
	 * @throws DMLRuntimeException if error occurs
	 */
	public static LibMatrixCuDNNConvolutionAlgorithm cudnnGetConvolutionForwardAlgorithm(
			GPUContext gCtx, String instName, int N, int C, int H, int W, int K, int R, int S, 
			int pad_h, int pad_w, int stride_h, int stride_w, int P, int Q, long workspaceLimit) throws DMLRuntimeException {
		long t1 = GPUStatistics.DISPLAY_STATISTICS ? System.nanoTime() : 0;
		LibMatrixCuDNNConvolutionAlgorithm ret = new LibMatrixCuDNNConvolutionAlgorithm(gCtx, instName, N, C, H, W, K, R, S, 
				pad_h, pad_w, stride_h, stride_w, P, Q);
		int[] algos = {-1};
		long sizeInBytesArray[] = {Math.min(workspaceLimit, MAX_WORKSPACE_LIMIT_BYTES)};
		jcuda.jcudnn.JCudnn.cudnnGetConvolutionForwardAlgorithm(LibMatrixCuDNN.getCudnnHandle(gCtx), 
				ret.nchwTensorDesc, ret.filterDesc, ret.convDesc, ret.nkpqTensorDesc,
				cudnnConvolutionFwdPreference.CUDNN_CONVOLUTION_FWD_SPECIFY_WORKSPACE_LIMIT, sizeInBytesArray[0], algos);
		jcuda.jcudnn.JCudnn.cudnnGetConvolutionForwardWorkspaceSize(LibMatrixCuDNN.getCudnnHandle(gCtx), 
				ret.nchwTensorDesc, ret.filterDesc, ret.convDesc, ret.nkpqTensorDesc, algos[0], sizeInBytesArray);
		if (sizeInBytesArray[0] != 0)
			ret.workSpace = gCtx.allocate(sizeInBytesArray[0]);
		ret.sizeInBytes = sizeInBytesArray[0];
		ret.algo = algos[0];
		if (GPUStatistics.DISPLAY_STATISTICS)
			GPUStatistics.maintainCPMiscTimes(instName, GPUInstruction.MISC_TIMER_CUDNN_INIT, System.nanoTime() - t1);
		return ret;
	}
	
	/**
	 * Factory method to get the algorithm wrapper for convolution backward filter
	 * 
	 * @param gCtx     a valid {@link GPUContext}
	 * @param instName the invoking instruction's name for record {@link org.apache.sysml.utils.Statistics}.
	 * @param N        number of input images
	 * @param C        number of channels
	 * @param H        height of each image
	 * @param W        width of each image
	 * @param K        number of output "channels"
	 * @param R        height of filter
	 * @param S        width of filter
	 * @param pad_h    padding height
	 * @param pad_w    padding width
	 * @param stride_h stride height
	 * @param stride_w string width
	 * @param P        output height
	 * @param Q        output width
	 * @param workspaceLimit maximum intermediate memory to use
	 * @return algorithm wrapper
	 * @throws DMLRuntimeException if error occurs
	 */
	public static LibMatrixCuDNNConvolutionAlgorithm cudnnGetConvolutionBackwardFilterAlgorithm(
			GPUContext gCtx, String instName, int N, int C, int H, int W, int K, int R, int S, 
			int pad_h, int pad_w, int stride_h, int stride_w, int P, int Q, long workspaceLimit) throws DMLRuntimeException {
		long t1 = GPUStatistics.DISPLAY_STATISTICS ? System.nanoTime() : 0;
		LibMatrixCuDNNConvolutionAlgorithm ret = new LibMatrixCuDNNConvolutionAlgorithm(gCtx, instName, N, C, H, W, K, R, S, 
				pad_h, pad_w, stride_h, stride_w, P, Q);
		
		int[] algos = {-1};
		long sizeInBytesArray[] = {Math.min(workspaceLimit, MAX_WORKSPACE_LIMIT_BYTES)};
		jcuda.jcudnn.JCudnn.cudnnGetConvolutionBackwardFilterAlgorithm(
				LibMatrixCuDNN.getCudnnHandle(gCtx), 
				ret.nchwTensorDesc, ret.nkpqTensorDesc, ret.convDesc, ret.filterDesc, 
				cudnnConvolutionBwdFilterPreference.CUDNN_CONVOLUTION_BWD_FILTER_SPECIFY_WORKSPACE_LIMIT, sizeInBytesArray[0], algos);
		jcuda.jcudnn.JCudnn.cudnnGetConvolutionBackwardFilterWorkspaceSize(LibMatrixCuDNN.getCudnnHandle(gCtx), 
				ret.nchwTensorDesc, ret.nkpqTensorDesc, ret.convDesc, ret.filterDesc, algos[0], sizeInBytesArray);
		if (sizeInBytesArray[0] != 0)
			ret.workSpace = gCtx.allocate(sizeInBytesArray[0]);
		ret.sizeInBytes = sizeInBytesArray[0];
		ret.algo = algos[0];
		
		if (GPUStatistics.DISPLAY_STATISTICS)
			GPUStatistics.maintainCPMiscTimes(instName, GPUInstruction.MISC_TIMER_CUDNN_INIT, System.nanoTime() - t1);
		return ret;
	}
	
	/**
	 * Factory method to get the algorithm wrapper for convolution backward data
	 * 
	 * @param gCtx     a valid {@link GPUContext}
	 * @param instName the invoking instruction's name for record {@link org.apache.sysml.utils.Statistics}.
	 * @param N        number of input images
	 * @param C        number of channels
	 * @param H        height of each image
	 * @param W        width of each image
	 * @param K        number of output "channels"
	 * @param R        height of filter
	 * @param S        width of filter
	 * @param pad_h    padding height
	 * @param pad_w    padding width
	 * @param stride_h stride height
	 * @param stride_w string width
	 * @param P        output height
	 * @param Q        output width
	 * @param workspaceLimit maximum intermediate memory to use
	 * @return algorithm wrapper
	 * @throws DMLRuntimeException if error occurs
	 */
	public static LibMatrixCuDNNConvolutionAlgorithm cudnnGetConvolutionBackwardDataAlgorithm(
			GPUContext gCtx, String instName, int N, int C, int H, int W, int K, int R, int S, 
			int pad_h, int pad_w, int stride_h, int stride_w, int P, int Q, long workspaceLimit) throws DMLRuntimeException {
		long t1 = GPUStatistics.DISPLAY_STATISTICS ? System.nanoTime() : 0;
		LibMatrixCuDNNConvolutionAlgorithm ret = new LibMatrixCuDNNConvolutionAlgorithm(gCtx, instName, N, C, H, W, K, R, S, 
				pad_h, pad_w, stride_h, stride_w, P, Q);
		
		// CuDNN's cudnnGetConvolutionBackwardDataAlgorithm returns CUDNN_CONVOLUTION_BWD_DATA_ALGO_1 for atleast one scenario 
		// for sentence CNN (N=1, C=1, H=2060, W=300, F=500, Hf=5, Wf=300, sparsity=0.1).
		// This causes more than 100x slowdown when compared with CUDNN_CONVOLUTION_BWD_DATA_ALGO_0.
		// To keep things simple for now, we will always prefer to use memory-less operator: CUDNN_CONVOLUTION_BWD_DATA_ALGO_0
		ret.algo = jcuda.jcudnn.cudnnConvolutionBwdDataAlgo.CUDNN_CONVOLUTION_BWD_DATA_ALGO_0;
//		int[] algos = {-1};
//		long sizeInBytesArray[] = {Math.min(workspaceLimit, MAX_WORKSPACE_LIMIT_BYTES)};
//		jcuda.jcudnn.JCudnn.cudnnGetConvolutionBackwardDataAlgorithm(
//				LibMatrixCuDNN.getCudnnHandle(gCtx), 
//				ret.filterDesc, ret.nkpqTensorDesc, ret.convDesc, ret.nchwTensorDesc,
//				cudnnConvolutionBwdDataPreference.CUDNN_CONVOLUTION_BWD_DATA_SPECIFY_WORKSPACE_LIMIT, sizeInBytesArray[0], algos);
//		jcuda.jcudnn.JCudnn.cudnnGetConvolutionBackwardDataWorkspaceSize(LibMatrixCuDNN.getCudnnHandle(gCtx), 
//				ret.filterDesc, ret.nkpqTensorDesc, ret.convDesc, ret.nchwTensorDesc, algos[0], sizeInBytesArray);
//		if (sizeInBytesArray[0] != 0)
//			ret.workSpace = gCtx.allocate(sizeInBytesArray[0]);
//		ret.sizeInBytes = sizeInBytesArray[0];
//		ret.algo = algos[0];
//		if (GPUStatistics.DISPLAY_STATISTICS)
//			GPUStatistics.maintainCPMiscTimes(instName, GPUInstruction.MISC_TIMER_CUDNN_INIT, System.nanoTime() - t1);
		return ret;
	}
	
	/**
	 * Convenience method to get tensor descriptor
	 * @param N number of images
	 * @param C number of channels
	 * @param H height
	 * @param W width
	 * @return cudnn tensor descriptor
	 * @throws DMLRuntimeException if the input descriptor and matrix dimensions don't match
	 */
	private static cudnnTensorDescriptor allocateTensorDescriptor(int N, int C, int H, int W) throws DMLRuntimeException {
		cudnnTensorDescriptor tensorDescriptor = new cudnnTensorDescriptor();
		cudnnCreateTensorDescriptor(tensorDescriptor);
		cudnnSetTensor4dDescriptor(tensorDescriptor, CUDNN_TENSOR_NCHW, CUDNN_DATA_DOUBLE, N, C, H, W);
		return tensorDescriptor;
	}
	
	private static cudnnFilterDescriptor allocateFilterDescriptor(int K, int C, int R, int S) {
		cudnnFilterDescriptor filterDesc = new cudnnFilterDescriptor();
		cudnnCreateFilterDescriptor(filterDesc);
		cudnnSetFilter4dDescriptor(filterDesc, CUDNN_DATA_DOUBLE, CUDNN_TENSOR_NCHW, K, C, R, S);
		return filterDesc;
	}
	
	private static cudnnConvolutionDescriptor allocateConvolutionDescriptor(int padding [], int strides []) {
		cudnnConvolutionDescriptor convDesc = new cudnnConvolutionDescriptor();
		cudnnCreateConvolutionDescriptor(convDesc);
		cudnnSetConvolution2dDescriptor(convDesc, padding[0], padding[1], strides[0], strides[1], 1, 1, CUDNN_CROSS_CORRELATION);
		return convDesc;
	}
}
