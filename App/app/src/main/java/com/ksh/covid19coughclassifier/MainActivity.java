package com.ksh.covid19coughclassifier;


import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

import org.tensorflow.lite.Interpreter;

import com.ksh.jlibrosa.JLibrosa;
import com.ksh.jlibrosa.exception.FileFormatNotSupportedException;
import com.ksh.jlibrosa.wavFile.WavFileException;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import biz.k11i.xgboost.Predictor;
import omrecorder.AudioRecordConfig;
import omrecorder.OmRecorder;
import omrecorder.AudioChunk;
import omrecorder.PullTransport;
import omrecorder.PullableSource;
import omrecorder.Recorder;

import static java.lang.String.format;
import static java.lang.Thread.sleep;

public class MainActivity extends AppCompatActivity {

    Predictor predictor;
    Interpreter tflite;
    Recorder recorder;

    Button button_record;
    Button button_classify;

    TextView selectFile_textView;
    TextView mfccTextView;
    TextView commentTextView;

    ProgressBar progressBar;

    TimerTask task = null;
    Timer timer = null;

    MfccTextHandler handler;

    //MediaRecorder recorder;

    File file_save_path;
    String filename;
    String date_time;
    String selectFileName;

    int second = 0;

    float [][] res_mfcc;
    float[][] output_data = new float[1][2];

    boolean mfcc_state;

    boolean listview_select_value = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);



        String permisson_check = "";

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        {
            permisson_check += Manifest.permission.RECORD_AUDIO + " ";
        }

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        {
            permisson_check += Manifest.permission.READ_EXTERNAL_STORAGE + " ";
        }

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        {
            permisson_check += Manifest.permission.WRITE_EXTERNAL_STORAGE + " ";
        }

        if(!TextUtils.isEmpty(permisson_check))
        {
            ActivityCompat.requestPermissions(this, permisson_check.trim().split(" "),1);
        }
        else
        {
                Toast.makeText(this, "권한 모두 허용함", Toast.LENGTH_SHORT).show();
        }





        selectFile_textView = (TextView)findViewById(R.id.selectFile_textView);
        mfccTextView = (TextView)findViewById(R.id.mfccTextView);
        commentTextView = (TextView)findViewById(R.id.commentTextView);

        progressBar = (ProgressBar) findViewById(R.id.progress_circular);



        tflite = getTfliteInterpreter("covid19_cough_classifier_v2.tflite");


        /*
        try {
            File xgboostDir = getFilesDir();
            String xgboostPath = xgboostDir.toString() + "cough_classifier";
            InputStream xgboostStream = new ByteArrayInputStream(xgboostPath.getBytes());

            //String xgboostModel = "cough_classifier.model";

            //AssetFileDescriptor modelDescriptor = getApplication().getAssets().openFd(xgboostModel);
            //InputStream xgboostFileStream = new FileInputStream(modelDescriptor.getFileDescriptor());

            predictor = new Predictor(xgboostStream);

            Log.i("inputStream", xgboostStream.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
         */


        View dialogView = getLayoutInflater().inflate(R.layout.custom_dialog, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);

        AlertDialog alertDialog = builder.create();

        button_record = (Button)findViewById(R.id.record);
        button_record.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                button_record.setEnabled(false);
                button_classify.setEnabled(false);

                long now = System.currentTimeMillis();
                Date mDate = new Date(now);

                SimpleDateFormat simpleDate = new SimpleDateFormat("yyyyMMddhhmmss", java.util.Locale.KOREA);
                date_time = simpleDate.format(mDate);

                file_save_path = getFilesDir();
                Log.i("file_save_path", file_save_path.toString());
                Log.i("date_time", date_time);
                File file = new File(file_save_path, date_time + ".wav");
                filename = file.getAbsolutePath();

                /*
                recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
                recorder.setOutputFile(filename);
                 */

                setupRecorder();

                second = 0;

                final TextView textView1 = (TextView)dialogView.findViewById(R.id.textView1);
                final TextView textView2 = (TextView)dialogView.findViewById(R.id.textView2);

                textView1.setVisibility(View.INVISIBLE);

                timer = new Timer();
                task = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        MainActivity.this.runOnUiThread(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                if(second == 0)
                                {
                                    textView2.setText("녹음 준비중...");
                                }
                                else if(second > 1 && second < 7)
                                {
                                    String timeText = Integer.toString(7 - second) + "초 뒤에 녹음 시작합니다\n\n최소 4번 이상 기침을 해주세요";
                                    textView2.setText(timeText);
                                }
                                else if(second == 7)
                                {
                                    recorder.startRecording();

                                    textView1.setVisibility(View.VISIBLE);
                                    textView1.setText("Recording");
                                    textView1.setTextColor(Color.RED);

                                    String timeText = Integer.toString(12 - second) + "초 남았습니다";

                                    textView2.setText(timeText);
                                }
                                else if(second > 7 && second < 12)
                                {
                                    String timeText = Integer.toString(12 - second) + "초 남았습니다";
                                    textView2.setText(timeText);
                                }
                                else if(second == 12)
                                {
                                    try {
                                        recorder.stopRecording();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }

                                    /*
                                    button_record.post(new Runnable()
                                    {
                                        @Override public void run()
                                        {
                                            animateVoice(0);
                                        }
                                    });
                                     */

                                    textView1.setText("Success");
                                    textView1.setTextColor(Color.GRAY);

                                    textView2.setText("녹음 종료중...");
                                }
                                else if(second == 15)
                                {
                                    if (task != null)
                                        task.cancel();

                                    alertDialog.dismiss();

                                    button_record.setEnabled(true);
                                    button_classify.setEnabled(true);
                                }

                                second++;
                            }
                        });
                    }
                };


                alertDialog.setCancelable(false);
                alertDialog.setCanceledOnTouchOutside(false);
                Objects.requireNonNull(alertDialog.getWindow()).setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                alertDialog.show();

                timer.schedule(task,0, 1000);
            }
        });




        View listview_view = getLayoutInflater().inflate(R.layout.custom_listview, null);

        AlertDialog.Builder listview_builder = new AlertDialog.Builder(this);
        listview_builder.setView(listview_view);

        AlertDialog listview_alertDialog = listview_builder.create();

        button_classify = (Button)findViewById(R.id.classify);
        button_classify.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                ListView listView = (ListView)listview_view.findViewById(R.id.dialog_listView);

                File getFileListPath = getFilesDir();
                File[] files = getFileListPath.listFiles();

                List<String> fileList = new ArrayList<>();

                if(files != null)
                {
                    for (File file : files) {
                        fileList.add(file.getName());
                    }
                }



                ArrayAdapter<String> adapter = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_list_item_1, fileList);

                listView.setAdapter(adapter);
                listView.setOnItemClickListener((adapterView, view1, position, id) -> {

                    commentTextView.setVisibility(View.INVISIBLE);
                    progressBar.setVisibility(View.VISIBLE);

                    mfccTextView.setText("분석중...");

                    mfcc_state = false;



                    selectFileName = (String)adapterView.getItemAtPosition(position);
                    Log.i("select_file", selectFileName);

                    listview_alertDialog.dismiss();

                    button_record.setEnabled(false);
                    button_classify.setEnabled(false);


                    selectFile_textView.setText(selectFileName);

                    //mfccTextView.setText("오디오 파일 분석중...");

                    handler = new MfccTextHandler();

                    MfccTextThread textThread = new MfccTextThread();
                    textThread.start();

                    MfccThread thread = new MfccThread();
                    thread.start();


                    //while(!mfcc_state)
                    //{
                        //progressBar.setVisibility(View.INVISIBLE);

                        //String mfccText = res_mfcc.length + ", " + res_mfcc[0].length;

                        //mfccTextView.setText(mfccText);
                    //}


                    /*
                    try {
                        res_mfcc = func_run(selectFileName);

                        progressBar.setVisibility(View.INVISIBLE);

                        String mfccText = res_mfcc.length + ", " + res_mfcc[0].length;

                        mfccTextView.setText(mfccText);

                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (WavFileException e) {
                        e.printStackTrace();
                    } catch (FileFormatNotSupportedException e) {
                        e.printStackTrace();
                    }

                     */





                });



                Objects.requireNonNull(listview_alertDialog.getWindow()).setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                listview_alertDialog.show();


            }
        });
    }




    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
         if(requestCode == 1)
         {
             int length = permissions.length;

             for (int i = 0; i < length; i++)
             {
                 if (grantResults[i] == PackageManager.PERMISSION_GRANTED)
                 {
                     Log.d("MainActivity","권한 허용 : " + permissions[i]);
                 }
             }
         }
    }







    private void setupRecorder()
    {
        recorder = OmRecorder.wav(new PullTransport.Default(mic(), new PullTransport.OnAudioChunkPulledListener()
        {
            @Override
            public void onAudioChunkPulled(AudioChunk audioChunk)
            {
                //animateVoice((float) (audioChunk.maxAmplitude() / 200.0));
            }
        }), file());
    }

    private void animateVoice(final float maxPeak) {
        button_record.animate().scaleX(1 + maxPeak).scaleY(1 + maxPeak).setDuration(10).start();
    }

    private PullableSource mic()
    {
        return new PullableSource.Default(new AudioRecordConfig.Default(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_IN_MONO, 44100)
        );
    }

    @NonNull
    private File file()
    {
        return new File(file_save_path, date_time + ".wav");
    }




    public float[][] func_run(String load_fileName) throws IOException, WavFileException, FileFormatNotSupportedException
    {
        File filePath = getFileStreamPath(load_fileName);

        String audioFilePath = filePath.getPath();

        Log.i("func_run", audioFilePath);


        int defaultSampleRate = -1;		//-1 value implies the method to use default sample rate
        int defaultAudioDuration = -1;	//-1 value implies the method to process complete audio duration

        JLibrosa jLibrosa = new JLibrosa();

        /* To read the magnitude values of audio files - equivalent to librosa.load('../audioFiles/sample2.wav', sr=None) function */

        float [] audioFeatureValues = jLibrosa.loadAndRead(audioFilePath, defaultSampleRate, defaultAudioDuration);

        ArrayList<Float> audioFeatureValuesList = jLibrosa.loadAndReadAsList(audioFilePath, defaultSampleRate, defaultAudioDuration);



        Log.i("FeatureLength", "length " + audioFeatureValues.length);

        //Log.i("FeatureLength", "value[50000] " + String.format("%.6f%n", audioFeatureValues[50000]));

        for(int i=0;i<10;i++) {
            Log.i("audioFeatureValues", format("%.6f%n", audioFeatureValues[i]));
        }


        /* To read the no of frames present in audio file*/
        //int nNoOfFrames = jLibrosa.getNoOfFrames();


        /* To read sample rate of audio file */
        int sampleRate = jLibrosa.getSampleRate();

        /* To read number of channels in audio file */
        //int noOfChannels = jLibrosa.getNoOfChannels();

        //Complex[][] stftComplexValues = jLibrosa.generateSTFTFeatures(audioFeatureValues, sampleRate, 40);



        //float[] invSTFTValues = jLibrosa.generateInvSTFTFeatures(stftComplexValues, sampleRate, 40);


        //float [][] melSpectrogram = jLibrosa.generateMelSpectroGram(audioFeatureValues, sampleRate, 2048, 128, 256);

        Log.i("test", "/n/n");
        Log.i("test", "***************************************");
        Log.i("test", "***************************************");
        Log.i("test", "***************************************");
        Log.i("test", "/n/n");


        /* To read the MFCC values of an audio file
         *equivalent to librosa.feature.mfcc(x, sr, n_mfcc=40) in python
         * */

        float[][] mfccValues = jLibrosa.generateMFCCFeatures(audioFeatureValues, sampleRate, 128);

        //float[] meanMFCCValues = jLibrosa.generateMeanMFCCFeatures(mfccValues, mfccValues.length, mfccValues[0].length);

        Log.i("test", ".......");
        Log.i("Size of MFCC Feature Values", mfccValues.length + " , " + mfccValues[0].length);

        //Log.i("mfccValues", "mfccValues[127][900]" + String.format("%.6f%n", mfccValues[127][900]));

        for(int i=0;i<1;i++) {
            for(int j=0;j<10;j++) {
                Log.i("mfccValues", format("%.6f%n", mfccValues[i][j]));
            }
        }

        return mfccValues;
    }




    public float[][][][] predict()
    {
        float[][][][] input_data = new float[1][128][128][1];

        for(int i = 0; i < 128; i++)
        {
            for(int k = 0; k < 128; k++)
            {
                input_data[0][i][k][0] = res_mfcc[i][k];
            }
        }

        return input_data;
    }




    private Interpreter getTfliteInterpreter(String model)
    {
        try {
            return new Interpreter(loadModelFile(MainActivity.this, model));
        }
        catch (Exception e){
            e.printStackTrace();
        }

        return null;
    }


    private MappedByteBuffer loadModelFile(Activity activity, String model) throws IOException
    {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(model);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();

        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();

        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

    }






    class MfccThread extends Thread
    {
        @Override
        public void run()
        {
            try {
                res_mfcc = func_run(selectFileName);

                float[][][][] input_data = predict();

                Log.i("input_data", input_data.length + ", " + input_data[0].length + ", " + input_data[0][0].length + ", " + input_data[0][0][0].length);

                tflite.run(input_data, output_data);

                Log.i("output_data_length", output_data.length + ", " + output_data[0].length);
                Log.i("output_data", output_data[0][0] + ", " + output_data[0][1]);



                mfcc_state = true;

                Log.i("mfcc_state", Boolean.toString(mfcc_state));
            } catch (IOException e) {
                e.printStackTrace();
            } catch (WavFileException e) {
                e.printStackTrace();
            } catch (FileFormatNotSupportedException e) {
                e.printStackTrace();
            }
        }
    }

    class MfccTextThread extends Thread
    {
        @Override
        public void run()
        {
            Log.i("MfccTextThread_Run", "running");
            while(true)
            {
                if(mfcc_state)
                {
                    Log.i("MfccTextThread", "success");
                    Message message = handler.obtainMessage();

                    Bundle bundle = new Bundle();
                    bundle.putInt(selectFileName, 1);
                    message.setData(bundle);

                    handler.sendMessage(message);

                    break;
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.i("MfccTextThread_Run", "stop");
        }
    }

    class MfccTextHandler extends Handler
    {
        @Override
        public void handleMessage(@NonNull Message msg)
        {
            super.handleMessage(msg);

            Bundle bundle = msg.getData();

            int data = bundle.getInt(selectFileName);

            if(data == 1)
            {
                //String mfccText = res_mfcc.length + ", " + res_mfcc[0].length;

                String mfccText = "Healthy : " + format("%.2f", output_data[0][0] * 100) + "%  |  COVID19 : " + format("%.2f", output_data[0][1] * 100) + "%";

                progressBar.setVisibility(View.INVISIBLE);

                mfccTextView.setText(mfccText);

                if(output_data[0][0] < 0.7)
                {
                    commentTextView.setVisibility(View.VISIBLE);
                }

                button_record.setEnabled(true);
                button_classify.setEnabled(true);
            }
        }
    }
}