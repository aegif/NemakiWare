export default function makeCancellablePromise(promise) {
    let isCancelled = false;
    const wrappedPromise = new Promise((resolve, reject) => {
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
