export default function makeCancellablePromise<T>(promise: Promise<T>): {
  promise: Promise<T>;
  cancel(): void;
} {
  let isCancelled = false;

  const wrappedPromise: Promise<T> = new Promise((resolve, reject) => {
    promise
      .then((value) => !isCancelled && resolve(value))
      .catch((error) => !isCancelled && reject(error));
  });

  return {
    promise: wrappedPromise,
    cancel() {
      isCancelled = true;
    },
  };
}
