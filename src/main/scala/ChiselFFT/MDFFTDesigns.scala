package ChiselFFT
import FFTDesigns._
import chisel3._
import chisel3.util._
import ComplexModules.FPComplex._
import implementation._
import IEEEConversions.FPConvert._
import Chisel.{MuxLookup, log2Ceil, resetToBool}
import ComplexModules.FPComplex
import implementation.ComplexNumbers.cmplx
import scala.collection.mutable

object MDFFTDesigns {
  class FFT2D(N: Int, r: Int,bw:Int) extends Module{ // simple non-streaming version
    val io = IO(new Bundle() {
      val in = Input(Vec(N*N, new ComplexNum(bw)))
      val in_ready = Input(Bool())
      val out_validate = Output(Bool())
      val out = Output(Vec(N*N, new ComplexNum(bw)))
    })
    val FFT1Ds = VecInit.fill(N)(Module(new FFT_sr_v2_nrv(N,r,N,bw)).io) // we can reuse
    val Permutes = Module(new PermutationsBasic(N*N,N,0,bw)).io // one permutation module
    val FFT1DLatency = getFFTLatency(N,r,N,bw)
    val TotalLatency = FFT1DLatency*2
    val regdelays = RegInit(VecInit.fill(TotalLatency)(false.B))
    val out_regdelay = RegInit(false.B)
    val out_regs = RegInit(VecInit.fill(N*N)(0.U.asTypeOf(new ComplexNum(bw))))
    out_regdelay := regdelays(TotalLatency-1)
    io.out_validate := out_regdelay
    io.out := out_regs
    //    io.out_test := Permutes.out
    for(i <- 1 until TotalLatency){
      regdelays(i) := regdelays(i-1)
    }
    for(i <- 0 until N){
      for(j <- 0 until N){
        FFT1Ds(i).in(j) := Permutes.out(i*N + j)
      }
    }
    when(regdelays(TotalLatency-1)){
      for(i <- 0 until N){
        for(j<- 0 until N){
          out_regs(i*N+j) := FFT1Ds(i).out(j)
        }
      }
    }.otherwise{
      out_regs := VecInit.fill(N*N)(0.U.asTypeOf(new ComplexNum(bw)))
    }
    when(io.in_ready){
      regdelays(0) := io.in_ready
      Permutes.in.zipWithIndex.map(x=>x._1 := io.in(x._2))
    }.otherwise{
      regdelays(0) := false.B
      for(i <- 0 until N){
        for(j <- 0 until N){
          Permutes.in(i*N + j) := FFT1Ds(i).out(j)
        }
      }
    }
  }

  def getfftstreamingmrlatency(N:Int, nr: Int, ns: Int, r: Int, s: Int, w: Int, bw: Int): Int = { // streaming single radix fft, still in progress
    val w1 = w // this will be the input and output width
    var w2 = Permutations.getw2(w1, N, nr, ns, s)
    var delay_cycles_stage2 = N / w1 - N / w2 // w2 will be bigger than w1
    // for the first N/w2 clock cycles, the output from the permutation should have a corresponding valid signal,
    // however, for the remaint N/w1-N/w2 clock cycles, the output from the permutation should have an invalid signal
    val CMultLatency = 2
    val T_L = CMultLatency
    var fftlatency1 = 0
    var fftlatency2 = 0
    val perm_latency1 = (N / w1) * 2
    if(w1 < nr && w2 < ns){
      fftlatency1 = getfftstreamedlatency(nr,r,w1,bw)
      fftlatency2 = getfftstreamedlatency(ns,s,w2,bw)
    }else if(w1 < nr && w2 >= ns){
      fftlatency1 = getfftstreamedlatency(nr,r,w1,bw)
      fftlatency2 = getFFTLatency(ns,s,ns,bw)
    }else if(w1 >= nr && w2 < ns){
      fftlatency1 = getFFTLatency(nr,r,nr,bw)
      fftlatency2 = getfftstreamedlatency(ns,s,w2, bw)
    }else if(w1 >= nr && w2 >= ns){
      fftlatency1 = getFFTLatency(nr,r,nr,bw)
      fftlatency2 = getFFTLatency(ns,s,ns,bw)
    }
    val total_latency = perm_latency1*3 + T_L + fftlatency1 + fftlatency2 + 1 // add 1 for output register
    total_latency
  }

  class FFT3D(N: Int, win: Int, nr: Int, ns: Int, r: Int, s: Int, bw:Int) extends Module{ // simple streaming version
    val io = IO(new Bundle() {
      val in = Input(Vec(win, new ComplexNum(bw)))
      val in_ready = Input(Bool())
      val out_validate = Output(Bool())
      val out = Output(Vec(win, new ComplexNum(bw)))
    })
    val FFT1Ds = VecInit.fill(3)(Module(new FFT_mr_v2_streamingv2(N,nr,ns,r,s,win,bw)).io) // we need three of em for streaming
    val Permutes = VecInit.fill(3)(Module(new PermutationsWithStreaming(N*N*N,N,N,win,0,bw)).io) // one permutation module

    val FFT1DLatency = getfftstreamingmrlatency(N,nr,ns,r,s,win,bw)
    val Permutes_latency = (N*N*N/win)*2
    val TotalLatency = FFT1DLatency*3 + Permutes_latency*3 + 1

    val perm_regdelays = RegInit(VecInit.fill(3)(VecInit.fill(Permutes_latency)(false.B)))
    val FFT1D_regdelays = RegInit(VecInit.fill(3)(VecInit.fill(FFT1DLatency)(false.B)))

    val out_regdelay = RegInit(false.B)
    val out_regs = RegInit(VecInit.fill(win)(0.U.asTypeOf(new ComplexNum(bw))))
    FFT1D_regdelays(0)(0) := io.in_ready
    FFT1D_regdelays(1)(0) := perm_regdelays(0)(Permutes_latency-1)
    FFT1D_regdelays(2)(0) := perm_regdelays(1)(Permutes_latency-1)
    out_regdelay :=          perm_regdelays(2)(Permutes_latency-1)
    perm_regdelays(0)(0) := FFT1D_regdelays(0)(FFT1DLatency-1)
    perm_regdelays(1)(0) := FFT1D_regdelays(1)(FFT1DLatency-1)
    perm_regdelays(2)(0) := FFT1D_regdelays(2)(FFT1DLatency-1)

    Permutes(0).in_en(Permutes_latency) := perm_regdelays(0)(Permutes_latency-1)
    Permutes(1).in_en(Permutes_latency) := perm_regdelays(1)(Permutes_latency-1)
    Permutes(2).in_en(Permutes_latency) := perm_regdelays(2)(Permutes_latency-1)
    Permutes(0).in_en(0) := FFT1D_regdelays(0)(FFT1DLatency-1)
    Permutes(1).in_en(0) := FFT1D_regdelays(1)(FFT1DLatency-1)
    Permutes(2).in_en(0) := FFT1D_regdelays(2)(FFT1DLatency-1)
    FFT1Ds(0).in_ready := io.in_ready
    FFT1Ds(1).in_ready := perm_regdelays(0)(Permutes_latency-1)
    FFT1Ds(2).in_ready := perm_regdelays(1)(Permutes_latency-1)
    when(io.in_ready){
      FFT1Ds(0).in := io.in
    }.otherwise{
      FFT1Ds(0).in := VecInit.fill(win)(0.U.asTypeOf(new ComplexNum(bw)))
    }

    FFT1Ds(1).in := Permutes(0).out
    FFT1Ds(2).in := Permutes(1).out
    Permutes(0).in := FFT1Ds(0).out
    Permutes(1).in := FFT1Ds(1).out
    Permutes(2).in := FFT1Ds(2).out
    for(i <- 0 until 3){
      for(j <- 1 until FFT1DLatency){
        FFT1D_regdelays(i)(j) := FFT1D_regdelays(i)(j-1)
      }
      for(j <- 1 until Permutes_latency){
        perm_regdelays(i)(j) := perm_regdelays(i)(j-1)
        Permutes(i).in_en(j) := perm_regdelays(i)(j-1)
      }
    }
    when(perm_regdelays(2)(Permutes_latency-1)){
      out_regs := Permutes(2).out
    }.otherwise{
      out_regs := VecInit.fill(win)(0.U.asTypeOf(new ComplexNum(bw)))
    }
    io.out_validate := out_regdelay
    io.out := out_regs
  }
}
