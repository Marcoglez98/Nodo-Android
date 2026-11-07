package com.nodo.messenger;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Foreground-only, mono PCM voice-call proof of concept for low-latency local Wi-Fi. */
final class AudioEngine {
    interface Sink{void audio(byte[] data);}
    private final Context context;private final Sink sink;
    private AudioRecord mic;private AudioTrack output;private AudioManager manager;
    private volatile boolean live=false,muted=false;private Thread thread,player;
    private final ArrayBlockingQueue<byte[]> audioQueue=new ArrayBlockingQueue<>(12);
    private AcousticEchoCanceler echo;private NoiseSuppressor noise;
    AudioEngine(Context c,Sink s){context=c;sink=s;}
    void start(){
        int minIn=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        int minOut=AudioTrack.getMinBufferSize(16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minIn<0||minOut<0)throw new IllegalStateException("Audio no compatible");
        mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minIn,4096));
        output=new AudioTrack(AudioManager.STREAM_VOICE_CALL,16000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minOut,4096),AudioTrack.MODE_STREAM);
        if(mic.getState()!=AudioRecord.STATE_INITIALIZED||output.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Dispositivo de audio no disponible");
        manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if(AcousticEchoCanceler.isAvailable())try{
            echo=AcousticEchoCanceler.create(mic.getAudioSessionId());if(echo!=null)echo.setEnabled(true);
        }catch(Exception ignored){}
        if(NoiseSuppressor.isAvailable())try{
            noise=NoiseSuppressor.create(mic.getAudioSessionId());if(noise!=null)noise.setEnabled(true);
        }catch(Exception ignored){}
        mic.startRecording();output.play();live=true;
        player=new Thread(()->{
            while(live){try{
                byte[] next=audioQueue.poll(100,TimeUnit.MILLISECONDS);
                if(next!=null && output!=null)output.write(next,0,next.length);
            }catch(InterruptedException e){break;}catch(Exception ignored){if(!live)break;}}
        },"nodo-voice-output");player.start();
        thread=new Thread(()->{
            byte[] buffer=new byte[640];
            while(live){int n=mic.read(buffer,0,buffer.length);if(n>0){
                byte[] copy=new byte[n];if(!muted)System.arraycopy(buffer,0,copy,0,n);
                sink.audio(copy);
            }}
        },"nodo-mic");thread.start();
    }
    void play(byte[] data){
        if(!live||data==null||data.length==0)return;
        if(!audioQueue.offer(data)){
            audioQueue.poll();audioQueue.offer(data); // Prefer newer voice packets when network jitter is high.
        }
    }
    void mute(boolean on){muted=on;}
    void speaker(boolean on){if(manager!=null)manager.setSpeakerphoneOn(on);}
    void stop(){
        live=false;if(player!=null)player.interrupt();audioQueue.clear();
        try{if(echo!=null){echo.release();echo=null;}}catch(Exception ignored){}
        try{if(noise!=null){noise.release();noise=null;}}catch(Exception ignored){}
        try{if(mic!=null){mic.stop();mic.release();}}catch(Exception ignored){}
        try{if(output!=null){output.stop();output.release();}}catch(Exception ignored){}
        if(manager!=null){manager.setSpeakerphoneOn(false);manager.setMode(AudioManager.MODE_NORMAL);}
    }
}
