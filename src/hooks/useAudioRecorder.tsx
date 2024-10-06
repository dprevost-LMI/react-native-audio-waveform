import { AudioWaveform } from '../AudioWaveform';
import type { IStartRecording } from '../types';

let nbOfPromises = 0;

const logPromise = async (promise: any, promiseName: string) => {
  try {
    nbOfPromises++;
    console.log(`Promise ${promiseName} has been called`);
    return await promise();
  } finally {
    nbOfPromises--;
    console.log(`Promise ${promiseName} has finished`);
    if (nbOfPromises > 0)
      console.log(`Number of promises remaining: ${nbOfPromises}`);
  }
};

export const useAudioRecorder = () => {
  const startRecording = (args?: Partial<IStartRecording>) =>
    AudioWaveform.startRecording(args);

  const stopRecording = (): Promise<string> =>
    logPromise(AudioWaveform.stopRecording, 'stopRecording');

  const pauseRecording = () =>
    logPromise(AudioWaveform.pauseRecording, 'pauseRecording');

  const resumeRecording = () =>
    logPromise(AudioWaveform.resumeRecording, 'resumeRecording');

  const getDecibel = () => logPromise(AudioWaveform.getDecibel, 'getDecibel');

  return {
    getDecibel,
    pauseRecording,
    resumeRecording,
    startRecording,
    stopRecording,
  };
};
